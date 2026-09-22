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

    public record CompiledShader(String msl,
                                 Map<String, Integer> vertexBuffers,
                                 Map<String, Integer> fragmentBuffers,
                                 Map<String, Integer> textures,
                                 Map<String, Integer> samplers,
                                 Map<String, Integer> inputs) {
    }

    public CompiledShader compile(String name, String source, ShaderType type) {
        IntermediaryShaderModule module;
        try {
            module = this.glsl.createIntermediary(name, source, type);
        } catch (Exception e) {
            throw new RuntimeException("GLSL -> SPIR-V failed for " + name + ": " + e.getMessage(), e);
        }
        int[] words;
        try {
            ByteBuffer spirv = module.spirv();
            words = new int[spirv.remaining() / 4];
            spirv.duplicate().asIntBuffer().get(words);
        } finally {
            module.close();
        }
        return translate(words, type);
    }

    private CompiledShader translate(int[] words, ShaderType type) {
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
                    Spvc.spvc_compiler_install_compiler_options(compiler, options);
                }

                PointerBuffer resourcesPtr = stack.mallocPointer(1);
                Spvc.spvc_compiler_create_shader_resources(compiler, resourcesPtr);
                long resources = resourcesPtr.get(0);

                int stage = type == ShaderType.VERTEX ? 0 : 4;  // spv::ExecutionModel
                collect(compiler, resources, stack, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, stage, buffers, null);
                collect(compiler, resources, stack, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, stage, textures, samplers);
                collect(compiler, resources, stack, Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS, stage, null, samplers);
                collectInputs(compiler, resources, stack, inputs);

                PointerBuffer result = stack.mallocPointer(1);
                check(Spvc.spvc_compiler_compile(compiler, result) == 0, "compile_msl", ctx);
                String msl = MemoryUtil.memUTF8(result.get(0));
                return new CompiledShader(msl, buffers, buffers, textures, samplers, inputs);
            } finally {
                Spvc.spvc_context_destroy(ctx);
            }
        }
    }

    private static final int DECORATION_BINDING = 33;
    private static final int DECORATION_DESCRIPTOR_SET = 34;

    /**
     * Fill the primary map (uniform buffers / textures) and optionally the secondary (samplers), and
     * force the MSL binding for each resource.
     *
     * <p>Two SPIRV-Cross details matter here. A uniform block's *variable* name is often empty (the
     * name lives on the block type), so the type name is used as a fallback. And
     * spvc_compiler_msl_get_automatic_resource_binding reports -1 before compilation, so the binding
     * is taken from the SPIR-V descriptor set/binding decorations and pushed explicitly instead.
     */
    private static void collect(long compiler, long resources, MemoryStack stack, int type, int stage,
                                Map<String, Integer> primary, Map<String, Integer> secondary) {
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
            String name = Spvc.spvc_compiler_get_name(compiler, resource.id());
            if (name == null || name.isEmpty()) {
                name = Spvc.spvc_compiler_get_name(compiler, resource.base_type_id());
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
                resourceBinding.msl_buffer(binding);
                if (primary != null) primary.put(name, binding);
            } else if (type == Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE) {
                resourceBinding.msl_texture(binding).msl_sampler(binding);
                if (primary != null) primary.put(name, binding);
                if (secondary != null) secondary.put(name, binding);
            } else if (type == Spvc.SPVC_RESOURCE_TYPE_SEPARATE_SAMPLERS) {
                resourceBinding.msl_sampler(binding);
                if (secondary != null) secondary.put(name, binding);
            }
            Spvc.spvc_compiler_msl_add_resource_binding(compiler, resourceBinding);
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
        this.glsl.close();
    }
}
