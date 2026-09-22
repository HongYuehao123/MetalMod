package net.metalmod.backend;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcMslResourceBinding;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * GLSL -> SPIR-V -> MSL, plus the resource bindings the Metal encoder needs.
 *
 * <p>Minecraft already ships the front half: {@link GlslCompiler} preprocesses defines and compiles
 * GLSL to SPIR-V with shaderc. This class adds the back half with SPIRV-Cross's MSL backend (also
 * already bundled), and reads back where SPIRV-Cross placed each resource so {@code setUniform}
 * /{@code bindTexture} can bind by name.
 */
public final class MetalShaderCompiler implements AutoCloseable {

    // SPIR-V decoration numbers (spirv.hpp SpvDecoration).
    private static final int DECORATION_LOCATION = 30;

    private final GlslCompiler glsl = new GlslCompiler();

    /**
     * The width of the 2D texture a texel buffer is presented as, in texels.
     *
     * <p>Metal has no buffer textures, so {@code spvTexelBufferCoord} flattens a linear texel index
     * with {@code (tc % W, tc / W)} and {@code W} is a literal in the generated MSL. The backing
     * texture must therefore be exactly this wide - a {@code texels x 1} texture reads the wrong
     * texels as soon as the index passes {@code W}, and one wider than the device limit is rejected
     * outright ({@code MTLTextureDescriptor has width (181818) greater than the maximum allowed size
     * of 16384}, which aborted the game on the first frame in a world).
     *
     * <p>4096 is SPIRV-Cross's own default, so every pipeline that compiled before this was pinned
     * still agrees with it; it is now set explicitly on both sides.
     */
    public static final int TEXEL_BUFFER_WIDTH = 4096;

    public record CompiledShader(String msl,
                                 Map<String, Integer> vertexBuffers,
                                 Map<String, Integer> fragmentBuffers,
                                 Map<String, Integer> textures,
                                 Map<String, Integer> samplers,
                                 Map<String, Integer> inputs) {
    }

    /** Both stages of one pipeline, with their shared varyings on matching locations. */
    public record CompiledPair(CompiledShader vertex, CompiledShader fragment) {
    }

    /**
     * Cache key for one compiled shader pair.
     *
     * <p>The roadmap asked for a cache keyed (id, type, defines), as
     * {@code VulkanDevice$ShaderCompilationKey} does. That key cannot be used for a stage here,
     * because a fragment stage's compiled form is not a function of its own source: varying
     * alignment (see {@link #compilePair}) rewrites its locations to match whichever vertex stage it
     * is paired with, so the same fragment shader paired with two differently-ordered vertex shaders
     * must compile to two different outputs. The pair is therefore the smallest safe unit.
     *
     * <p>Measured over the 87 vanilla pipelines, pair keying saves 70 of 174 stage compilations
     * against the 73 a stage-level cache would save - the same benefit without the hazard.
     */
    public record PairKey(net.minecraft.resources.Identifier vertex,
                          net.minecraft.resources.Identifier fragment,
                          net.minecraft.client.renderer.ShaderDefines defines) {
    }

    private final Map<PairKey, CompiledPair> pairCache = new HashMap<>();
    private int cacheHits;
    private int cacheMisses;

    public CompiledShader compile(String name, String source, ShaderType type) {
        return translate(name, toSpirv(name, source, type), type);
    }

    /**
     * Compile a vertex/fragment pair, reusing a previous result for the same shaders and defines.
     *
     * <p>The sources are supplied lazily so a cache hit also skips fetching and define-injecting them,
     * which is a real part of the cost when a pipeline is recompiled (resource reload, or the engine
     * announcing the same pipeline twice).
     */
    public CompiledPair compilePair(net.minecraft.resources.Identifier vertexId,
                                    net.minecraft.resources.Identifier fragmentId,
                                    net.minecraft.client.renderer.ShaderDefines defines,
                                    java.util.function.Supplier<String> vertexSource,
                                    java.util.function.Supplier<String> fragmentSource) {
        PairKey key = new PairKey(vertexId, fragmentId, defines);
        CompiledPair cached = this.pairCache.get(key);
        if (cached != null) {
            this.cacheHits++;
            return cached;
        }
        this.cacheMisses++;
        CompiledPair compiled = compilePair(vertexId.toString(), vertexSource.get(),
                fragmentId.toString(), fragmentSource.get());
        this.pairCache.put(key, compiled);
        return compiled;
    }

    /** Uncached pair compile, for callers that have no pipeline identity (tools, tests). */
    public CompiledPair compilePair(String vertexName, String vertexSource,
                                    String fragmentName, String fragmentSource) {
        int[] vertexWords = toSpirv(vertexName, vertexSource, ShaderType.VERTEX);
        int[] fragmentWords = toSpirv(fragmentName, fragmentSource, ShaderType.FRAGMENT);
        alignVaryings(vertexWords, fragmentWords);
        CompiledPair compiled = new CompiledPair(
                translate(vertexName, vertexWords, ShaderType.VERTEX),
                translate(fragmentName, fragmentWords, ShaderType.FRAGMENT));
        dumpIfRequested(vertexName, fragmentName, compiled);
        return compiled;
    }

    /**
     * Print the generated MSL when {@code -Dmetalmod.dumpMsl=<substring>} is set.
     *
     * <p>Diagnosing a wrong pixel means reading the MSL the way Metal sees it - the varyings, their
     * interpolation qualifiers and the {@code [[attribute(N)]]} indices are all decided here, and
     * none of them survive into the GLSL. {@code all} matches every pair.
     */
    private static void dumpIfRequested(String vertexName, String fragmentName, CompiledPair compiled) {
        String wanted = System.getProperty("metalmod.dumpMsl");
        if (wanted == null || wanted.isBlank()) {
            return;
        }
        if (!wanted.equals("all")
                && !vertexName.contains(wanted)
                && !fragmentName.contains(wanted)) {
            return;
        }
        System.out.println("################ VS MSL " + vertexName + "\n" + compiled.vertex().msl());
        System.out.println("################ FS MSL " + fragmentName + "\n" + compiled.fragment().msl());
    }

    /** One-line cache accounting, so the saving is observable rather than assumed. */
    public String cacheSummary() {
        return "shader pairs: compiled=" + this.cacheMisses + " reused=" + this.cacheHits
                + " cached=" + this.pairCache.size();
    }

    private int[] toSpirv(String name, String source, ShaderType type) {
        IntermediaryShaderModule module;
        try {
            module = this.glsl.createIntermediary(name, source, type);
        } catch (Exception e) {
            throw new RuntimeException("GLSL -> SPIR-V failed for " + name + ": " + e.getMessage(), e);
        }
        try {
            ByteBuffer spirv = module.spirv();
            int[] words = new int[spirv.remaining() / 4];
            // ByteBuffer.duplicate() resets the byte order to BIG_ENDIAN, which yields byte-swapped
            // SPIR-V words (magic 0x03022307 instead of 0x07230203). Handing those to SPIRV-Cross
            // happened to work because the native stack writes them back in native order, restoring
            // the original bytes - but any inspection of the words here needs the real values.
            spirv.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer().get(words);
            return words;
        } finally {
            module.close();
        }
    }

    // SPIR-V opcodes and enums used by the varying alignment below.
    private static final int OP_NAME = 5;
    private static final int OP_VARIABLE = 59;
    private static final int OP_DECORATE = 71;
    private static final int STORAGE_INPUT = 1;
    private static final int STORAGE_OUTPUT = 3;

    /** One user-declared stage interface variable (a varying), as it appears in the SPIR-V module. */
    private record Varying(int id, String name, int location) {
    }

    /**
     * Rewrite {@code fragmentWords} so every fragment input that shares a name with a vertex output
     * carries the vertex stage's location. Variables without a location (built-ins such as
     * {@code gl_Position}) and names the vertex stage does not declare are left untouched.
     *
     * <p>This exists because shaderc compiles each stage on its own and glslang assigns varying
     * locations per stage by declaration order - which they are not required to agree on.
     * {@code animate_sprite} / {@code animate_sprite_interpolate} is a live vanilla example: the
     * vertex stage emits {@code texCoord0} at location 0 and {@code fAnimationProgress} at 1, while
     * the fragment stage declares them the other way round. OpenGL links varyings by name so Mojang
     * never saw it, and SPIRV-Cross faithfully copies the decorations into MSL, where Metal rejects
     * the pipeline outright:
     *
     * <pre>Fragment input(s) `user(locn0),user(locn1)` mismatching vertex shader output type(s)</pre>
     *
     * <p>The vertex stage is the authority, because every fragment input must be satisfied by a
     * vertex output. Pipelines whose stages already agree are untouched, so this can only change
     * pipelines that previously failed.
     */
    private static void alignVaryings(int[] vertexWords, int[] fragmentWords) {
        Map<String, Integer> vertexOutputs = new HashMap<>();
        for (Varying varying : varyings(vertexWords, STORAGE_OUTPUT)) {
            if (varying.location() >= 0 && !varying.name().isEmpty()) {
                vertexOutputs.put(varying.name(), varying.location());
            }
        }
        if (vertexOutputs.isEmpty()) {
            return;
        }
        for (Varying input : varyings(fragmentWords, STORAGE_INPUT)) {
            if (input.location() < 0) {
                continue;
            }
            Integer wanted = vertexOutputs.get(input.name());
            if (wanted != null && wanted != input.location()) {
                rewriteLocation(fragmentWords, input.id(), wanted);
            }
        }
    }

    private static java.util.List<Varying> varyings(int[] words, int storageClass) {
        Map<Integer, String> names = new HashMap<>();
        Map<Integer, Integer> locations = new HashMap<>();
        Map<Integer, Integer> storage = new java.util.LinkedHashMap<>();
        for (int i = 5; i < words.length; ) {
            int wordCount = words[i] >>> 16;
            if (wordCount == 0) {
                break;
            }
            int opcode = words[i] & 0xFFFF;
            if (opcode == OP_NAME) {
                names.put(words[i + 1], readString(words, i + 2, wordCount - 2));
            } else if (opcode == OP_DECORATE) {
                if (words[i + 2] == DECORATION_LOCATION) {
                    locations.put(words[i + 1], words[i + 3]);
                }
            } else if (opcode == OP_VARIABLE) {
                storage.put(words[i + 2], words[i + 3]);
            }
            i += wordCount;
        }
        java.util.List<Varying> result = new java.util.ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : storage.entrySet()) {
            if (entry.getValue() == storageClass) {
                result.add(new Varying(entry.getKey(),
                        names.getOrDefault(entry.getKey(), ""),
                        locations.getOrDefault(entry.getKey(), -1)));
            }
        }
        return result;
    }

    /** Patch the literal of an existing {@code OpDecorate <id> Location <n>} in place. */
    private static void rewriteLocation(int[] words, int target, int location) {
        for (int i = 5; i < words.length; ) {
            int wordCount = words[i] >>> 16;
            if (wordCount == 0) {
                break;
            }
            if ((words[i] & 0xFFFF) == OP_DECORATE
                    && words[i + 1] == target
                    && words[i + 2] == DECORATION_LOCATION) {
                words[i + 3] = location;
                return;
            }
            i += wordCount;
        }
    }

    /** SPIR-V literal strings pack four bytes per word, little-endian, NUL-terminated. */
    private static String readString(int[] words, int start, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = start; i < start + count; i++) {
            for (int b = 0; b < 4; b++) {
                char c = (char) ((words[i] >> (8 * b)) & 0xFF);
                if (c == 0) {
                    return out.toString();
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    private CompiledShader translate(String name, int[] words, ShaderType type) {
        normalizeBindings(words);
        Map<String, Integer> buffers = new HashMap<>();
        Map<String, Integer> textures = new HashMap<>();
        Map<String, Integer> samplers = new HashMap<>();
        Map<String, Integer> inputs = new HashMap<>();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer ctxPtr = stack.mallocPointer(1);
            check(Spvc.spvc_context_create(ctxPtr) == 0, "context_create", 0L);
            long ctx = ctxPtr.get(0);
            try {
                IntBuffer spv = stack.ints(words);
                PointerBuffer parsed = stack.mallocPointer(1);
                check(Spvc.spvc_context_parse_spirv(ctx, spv, (long) words.length, parsed) == 0, "parse_spirv", ctx);

                PointerBuffer compilerPtr = stack.mallocPointer(1);
                check(Spvc.spvc_context_create_compiler(ctx, Spvc.SPVC_BACKEND_MSL, parsed.get(0),
                        Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, compilerPtr) == 0, "create_compiler", ctx);
                long compiler = compilerPtr.get(0);

                PointerBuffer optionsPtr = stack.mallocPointer(1);
                if (Spvc.spvc_compiler_create_compiler_options(compiler, optionsPtr) == 0) {
                    long options = optionsPtr.get(0);
                    Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, 20100);
                    Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM,
                            Spvc.SPVC_MSL_PLATFORM_MACOS);
                    // Metal has no buffer textures, so SPIRV-Cross emulates one as a 2D texture and
                    // emits `spvTexelBufferCoord(tc) { return uint2(tc % W, tc / W); }` with W baked
                    // in as a literal. Set W here rather than inheriting the default, so the width
                    // the shader divides by and the width the texture is laid out with cannot drift.
                    Spvc.spvc_compiler_options_set_uint(options,
                            Spvc.SPVC_COMPILER_OPTION_MSL_TEXEL_BUFFER_TEXTURE_WIDTH, TEXEL_BUFFER_WIDTH);
                    Spvc.spvc_compiler_install_compiler_options(compiler, options);
                }

                PointerBuffer resourcesPtr = stack.mallocPointer(1);
                Spvc.spvc_compiler_create_shader_resources(compiler, resourcesPtr);
                long resources = resourcesPtr.get(0);

                int stage = type == ShaderType.VERTEX ? 0 : 4;  // spv::ExecutionModel
                // The vertex stage reserves 0..15 for vertex-attribute slots; the fragment stage has
                // no attributes, so its uniform buffers may start at 0.
                Slots slots = new Slots();
                if (stage != 0) {
                    slots.buffer = 0;
                }
                collect(compiler, resources, stack, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, stage, buffers, null, slots);
                collect(compiler, resources, stack, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, stage, textures, samplers, slots);
                collect(compiler, resources, stack, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS, stage, null, samplers, slots);
                collectInputs(compiler, resources, stack, inputs);

                PointerBuffer result = stack.mallocPointer(1);
                check(Spvc.spvc_compiler_compile(compiler, result) == 0, "compile_msl", ctx);
                String msl = MemoryUtil.memUTF8(result.get(0));
                String stageName = type == ShaderType.VERTEX ? "vertex" : "fragment";
                checkUniqueSlots(stageName, "uniform buffer", buffers);
                checkUniqueSlots(stageName, "texture", textures);
                checkUniqueSlots(stageName, "sampler", samplers);
                verifyMslSlots(name, stageName, msl, buffers, textures, inputs);
                return new CompiledShader(msl, buffers, buffers, textures, samplers, inputs);
            } finally {
                Spvc.spvc_context_destroy(ctx);
            }
        }
    }

    private static final int DECORATION_BINDING = 33;
    private static final int DECORATION_DESCRIPTOR_SET = 34;

    /** Slots 0..15 are reserved for VertexFormat attribute layouts; uniform buffers start here. */
    private static final int VERTEX_BUFFER_INDEX_OFFSET = 16;

    /**
     * The next free MSL slot per resource kind for one stage. Counters rather than the SPIR-V
     * binding, because glslang emits duplicate bindings (see {@link #collect}).
     */
    private static final class Slots {
        int buffer = VERTEX_BUFFER_INDEX_OFFSET;
        int texture;
        int sampler;
    }

    /**
     * Fill the primary map (uniform buffers / textures) and optionally the secondary (samplers), and
     * force the MSL binding for each resource.
     *
     * <p>Three SPIRV-Cross details matter here. A uniform block's *variable* name is often empty (the
     * name lives on the block type), so the type name is used. The descriptor set/binding
     * decorations identify *which* SPIR-V resource a binding applies to, so they are still read from
     * the module. But the <b>MSL slot</b> must not come from them, because glslang emits duplicate
     * bindings: every shader that imports {@code fog.glsl} gets {@code Fog} at binding 0 alongside
     * another block at binding 0 as well. Mapping binding to slot therefore put two blocks in one
     * Metal buffer index, and binding one overwrote the other. MetalMod binds by name, so the slots
     * only have to be unique per stage — a counter gives that.
     */
    private static void collect(long compiler, long resources, MemoryStack stack, int type, int stage,
                                Map<String, Integer> primary, Map<String, Integer> secondary,
                                Slots slots) {
        PointerBuffer listPtr = stack.mallocPointer(1);
        PointerBuffer countPtr = stack.mallocPointer(1);
        if (Spvc.spvc_resources_get_resource_list_for_type(resources, type, listPtr, countPtr) != 0) {
            return;
        }
        long count = countPtr.get(0);
        if (count <= 0) {
            return;
        }
        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(listPtr.get(0), (int) count);
        for (int index = 0; index < count; index++) {
            SpvcReflectedResource resource = list.get(index);
            // Uniform blocks are keyed by their block TYPE name, everything else by its variable
            // name. The engine binds by the type name - its BindGroupLayouts declare "LightmapInfo",
            // and MC's own GlslCompiler.addToBindGroup builds those layouts from the same SPIR-V
            // block name - so keying on the GLSL instance name binds nothing.
            //
            // lightmap.fsh is the one vanilla shader that names its instance
            // (`layout(std140) uniform LightmapInfo { ... } lightmapInfo;`), which is exactly why the
            // lightmap was the only binding the unbound-binding diagnostic reported: every other
            // vanilla block omits the instance name, so the two conventions happened to agree.
            String name;
            if (type == Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER) {
                name = Spvc.spvc_compiler_get_name(compiler, resource.base_type_id());
                if (name == null || name.isEmpty()) {
                    name = Spvc.spvc_compiler_get_name(compiler, resource.id());
                }
            } else {
                name = Spvc.spvc_compiler_get_name(compiler, resource.id());
                if (name == null || name.isEmpty()) {
                    name = Spvc.spvc_compiler_get_name(compiler, resource.base_type_id());
                }
            }
            if (name == null || name.isEmpty()) {
                continue;
            }

            int set = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), DECORATION_DESCRIPTOR_SET);
            int binding = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), DECORATION_BINDING);
            if (set < 0) set = 0;
            if (binding < 0) binding = index;

            SpvcMslResourceBinding resourceBinding = SpvcMslResourceBinding.calloc(stack);
            resourceBinding.stage(stage).desc_set(set).binding(binding);
            if (type == Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER) {
                // Metal shares one buffer index space per stage between the vertex-attribute layouts
                // (MTLVertexDescriptor slots, which Minecraft numbers from 0) and the "constant"
                // buffers SPIRV-Cross emits for uniform blocks. Vulkan keeps those two namespaces
                // apart, so keep the low indices for attributes and start vertex-stage uniform
                // buffers above them.
                int mslBuffer = slots.buffer++;
                resourceBinding.msl_buffer(mslBuffer);
                if (primary != null) primary.put(name, mslBuffer);
            } else if (type == Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE) {
                int mslTexture = slots.texture++;
                int mslSampler = slots.sampler++;
                resourceBinding.msl_texture(mslTexture).msl_sampler(mslSampler);
                if (primary != null) primary.put(name, mslTexture);
                if (secondary != null) secondary.put(name, mslSampler);
            } else if (type == Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS) {
                int mslSampler = slots.sampler++;
                resourceBinding.msl_sampler(mslSampler);
                if (secondary != null) secondary.put(name, mslSampler);
            }
            Spvc.spvc_compiler_msl_add_resource_binding(compiler, resourceBinding);
        }
    }

    /**
     * Give every resource a unique SPIR-V {@code Binding} decoration.
     *
     * <p>glslang emits duplicates: every shader importing {@code fog.glsl} gets its {@code Fog} block
     * at binding 0 alongside another block at binding 0 as well. That is invalid SPIR-V, and
     * SPIRV-Cross keys {@code spvc_compiler_msl_add_resource_binding} on {@code (set, binding)} — so
     * with two resources on the same key it applies one of the bindings to the wrong block and the
     * MSL ends up disagreeing with the slot we recorded. Renumbering every Binding decoration makes
     * each resource identifiable, which is all the explicit MSL binding needs. MetalMod binds by
     * name, so the numbers themselves are arbitrary.
     */
    private static void normalizeBindings(int[] words) {
        java.util.List<Integer> targets = new java.util.ArrayList<>();
        for (int i = 5; i < words.length; ) {
            int wordCount = words[i] >>> 16;
            if (wordCount == 0) {
                break;
            }
            if ((words[i] & 0xFFFF) == OP_DECORATE && words[i + 2] == DECORATION_BINDING) {
                targets.add(words[i + 1]);
            }
            i += wordCount;
        }
        if (targets.isEmpty()) {
            return;
        }
        targets.sort(Integer::compareTo);
        Map<Integer, Integer> renumbered = new HashMap<>();
        int next = 0;
        for (int target : targets) {
            renumbered.put(target, next++);
        }
        for (int i = 5; i < words.length; ) {
            int wordCount = words[i] >>> 16;
            if (wordCount == 0) {
                break;
            }
            if ((words[i] & 0xFFFF) == OP_DECORATE && words[i + 2] == DECORATION_BINDING) {
                words[i + 3] = renumbered.get(words[i + 1]);
            }
            i += wordCount;
        }
    }

    // "constant Name& _12 [[buffer(3)]]" and "texture2d<float> Name [[texture(1)]]".
    private static final java.util.regex.Pattern MSL_BUFFER = java.util.regex.Pattern.compile(
            "constant\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*&\\s*\\w+\\s*\\[\\[buffer\\((\\d+)\\)\\]\\]");
    private static final java.util.regex.Pattern MSL_TEXTURE = java.util.regex.Pattern.compile(
            "texture2d(?:_array|_ms)?\\s*<[^>]+>\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*\\[\\[texture\\((\\d+)\\)\\]\\]");
    // "float3 Position [[attribute(0)]]" - the attribute index the shader will read.
    private static final java.util.regex.Pattern MSL_ATTRIBUTE = java.util.regex.Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_]*)\\s*\\[\\[attribute\\((\\d+)\\)\\]\\]");

    /**
     * Check that what the reflection recorded matches where SPIRV-Cross actually put each resource in
     * the MSL. A disagreement is a silent wrong-slot bind, so it is reported rather than assumed.
     */
    private static void verifyMslSlots(String shader, String stage, String msl,
                                       Map<String, Integer> buffers, Map<String, Integer> textures,
                                       Map<String, Integer> inputs) {
        checkMsl(shader, stage, "uniform buffer", MSL_BUFFER, msl, buffers);
        checkMsl(shader, stage, "texture", MSL_TEXTURE, msl, textures);
        // The vertex descriptor's attribute indices come from the same map the MSL is compared
        // against, so a disagreement would mean the shader reads one attribute slot while the
        // descriptor feeds another - wrong vertex data, silently.
        checkMsl(shader, stage, "vertex attribute", MSL_ATTRIBUTE, msl, inputs);
    }

    private static void checkMsl(String shader, String stage, String kind,
                                 java.util.regex.Pattern pattern, String msl,
                                 Map<String, Integer> expected) {
        java.util.regex.Matcher matcher = pattern.matcher(msl);
        while (matcher.find()) {
            String name = matcher.group(1);
            int slot = Integer.parseInt(matcher.group(2));
            Integer wanted = expected.get(name);
            if (wanted != null && wanted != slot) {
                MetalDevice.reportSlotMismatch(shader, stage, kind, name, wanted, slot);
            }
        }
    }

    /** Report any two resources sharing one Metal slot; see MetalDevice.reportSlotCollision. */
    private static void checkUniqueSlots(String stage, String kind, Map<String, Integer> slots) {
        Map<Integer, String> seen = new HashMap<>();
        for (Map.Entry<String, Integer> entry : slots.entrySet()) {
            String previous = seen.put(entry.getValue(), entry.getKey());
            if (previous != null) {
                MetalDevice.reportSlotCollision(stage, kind, previous, entry.getKey(), entry.getValue());
            }
        }
    }

    private static void collectInputs(long compiler, long resources, MemoryStack stack, Map<String, Integer> inputs) {
        PointerBuffer listPtr = stack.mallocPointer(1);
        PointerBuffer countPtr = stack.mallocPointer(1);
        if (Spvc.spvc_resources_get_resource_list_for_type(resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT,
                listPtr, countPtr) != 0) {
            return;
        }
        long count = countPtr.get(0);
        if (count <= 0) {
            return;
        }
        SpvcReflectedResource.Buffer list = SpvcReflectedResource.create(listPtr.get(0), (int) count);
        for (int index = 0; index < count; index++) {
            SpvcReflectedResource resource = list.get(index);
            inputs.put(Spvc.spvc_compiler_get_name(compiler, resource.id()),
                    Spvc.spvc_compiler_get_decoration(compiler, resource.id(), DECORATION_LOCATION));
        }
    }

    private static void check(boolean ok, String what, long ctx) {
        if (!ok) {
            String message = ctx == 0L ? "" : Spvc.spvc_context_get_last_error_string(ctx);
            throw new IllegalStateException("SPIRV-Cross " + what + " failed: " + message);
        }
    }

    @Override
    public void close() {
        this.pairCache.clear();
        this.glsl.close();
    }
}
