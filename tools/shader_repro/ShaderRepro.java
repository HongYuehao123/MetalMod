import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import net.metalmod.backend.MetalNative;
import net.metalmod.backend.MetalShaderCompiler;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Offline repro for a single vanilla shader pair: raw GLSL from the client jar -> SPIR-V -> MSL ->
 * MTLRenderPipelineState. Prints each stage's SPIR-V interface locations, both MSL sources, and
 * whether Metal accepted the pipeline. This exists because the game can only report "native pipeline
 * creation failed"; the reason (a stage-interface mismatch, a bad binding, a shader compile error)
 * is only visible with the shaders in hand.
 *
 * <p>Pipeline state here is representative rather than exact - it uses an RGBA8 colour target, no
 * depth, triangle-list topology and no vertex buffers, which is what the sprite, GUI and blit
 * pipelines use. Override the formats for pipelines that differ. Interface matching, the failure
 * this was written for, does not depend on that state.
 *
 * <p>Usage: tools/shader_repro/run.sh &lt;vertex-shader-path-in-jar&gt; &lt;fragment-shader-path-in-jar&gt;
 * <br>Direct: java -cp &lt;mod-classes&gt;:&lt;mc-classpath&gt; ShaderRepro &lt;include-dir&gt; &lt;vsh&gt; &lt;fsh&gt;
 * [colorFormat] [depthFormat]
 *
 * <p>Exits 0 when Metal created the pipeline, 1 otherwise, so it can gate a build.
 */
public final class ShaderRepro {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: ShaderRepro <include-dir> <vertex.shader> <fragment.shader>"
                    + " [colorFormat=70] [depthFormat=0]");
            System.exit(2);
        }
        Path includeDir = Paths.get(args[0]);
        String vertexPath = args[1];
        String fragmentPath = args[2];
        long colorFormat = args.length > 3 ? Long.parseLong(args[3]) : 70L;  // RGBA8Unorm
        long depthFormat = args.length > 4 ? Long.parseLong(args[4]) : 0L;   // none
        String vs = preprocess(Files.readString(Paths.get(vertexPath)), includeDir);
        String fs = preprocess(Files.readString(Paths.get(fragmentPath)), includeDir);

        System.out.println("################ VS GLSL (preprocessed)\n" + vs);
        System.out.println("################ FS GLSL (preprocessed)\n" + fs);

        try (GlslCompiler glsl = new GlslCompiler()) {
            dumpLocations(glsl, vertexPath, vs, ShaderType.VERTEX);
            dumpLocations(glsl, fragmentPath, fs, ShaderType.FRAGMENT);
        }

        try (MetalShaderCompiler compiler = new MetalShaderCompiler()) {
            MetalShaderCompiler.CompiledPair pair = compiler.compilePair(
                    vertexPath, vs, fragmentPath, fs);
            MetalShaderCompiler.CompiledShader cvs = pair.vertex();
            MetalShaderCompiler.CompiledShader cfs = pair.fragment();

            System.out.println("################ VS MSL\n" + cvs.msl());
            System.out.println("################ FS MSL\n" + cfs.msl());
            System.out.println("VS buffers=" + cvs.vertexBuffers() + " inputs=" + cvs.inputs());
            System.out.println("FS buffers=" + cfs.fragmentBuffers() + " textures=" + cfs.textures()
                    + " samplers=" + cfs.samplers());

            MemorySegment dev = MetalNative.deviceCreate();
            if (dev == null || dev.address() == 0) {
                throw new IllegalStateException("mmm_device_create returned NULL");
            }
            MemorySegment vlib = MetalNative.libraryCreate(dev, cvs.msl());
            MemorySegment flib = MetalNative.libraryCreate(dev, cfs.msl());
            if (vlib.address() == 0 || flib.address() == 0) {
                System.out.println("RESULT: a MSL stage failed to compile: " + MetalNative.lastError());
                System.exit(1);
            }
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment pipe = MetalNative.renderPipelineCreate(dev,
                        vlib, "main0", flib, "main0",
                        colorFormat, 15, 0,
                        1, 0, 0, 1, 0, 0,
                        depthFormat, 7, 0,
                        3 /* triangle list */, 1, 0, 0,
                        0.0f, 0.0f,
                        arena.allocate(1), 0, arena.allocate(1), 0);
                if (pipe.address() == 0) {
                    System.out.println("RESULT: Metal REJECTED the pipeline: " + MetalNative.lastError());
                    System.exit(1);
                }
                System.out.println("RESULT: pipeline created OK");
            }
        }
    }

    /**
     * The MSL {@code [[user(locnN)]]} index comes straight from the SPIR-V Location decoration, so
     * print those decorations per stage. This is what decides whether the two stages agree.
     */
    private static void dumpLocations(GlslCompiler glsl, String name, String source, ShaderType type)
            throws Exception {
        IntermediaryShaderModule module = glsl.createIntermediary(name, source, type);
        try {
            ByteBuffer spirv = module.spirv();
            IntBuffer words = spirv.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
            int[] w = new int[words.remaining()];
            words.get(w);

            Map<Integer, String> names = new LinkedHashMap<>();
            Map<Integer, Integer> locations = new LinkedHashMap<>();
            Map<Integer, Integer> storage = new LinkedHashMap<>();
            for (int i = 5; i < w.length; ) {
                int wordCount = w[i] >>> 16;
                int opcode = w[i] & 0xFFFF;
                if (wordCount == 0) break;
                if (opcode == 5) {                    // OpName target "name"
                    names.put(w[i + 1], str(w, i + 2, wordCount - 2));
                } else if (opcode == 71) {            // OpDecorate target decoration literals
                    if (w[i + 2] == 30) locations.put(w[i + 1], w[i + 3]);
                } else if (opcode == 59) {            // OpVariable type result storageClass
                    storage.put(w[i + 2], w[i + 3]);
                }
                i += wordCount;
            }
            System.out.println("---- SPIR-V stage interface: " + name + " (" + type + ") ----");
            for (Map.Entry<Integer, Integer> e : storage.entrySet()) {
                int sc = e.getValue();
                if (sc == 1 || sc == 3) {
                    System.out.printf("  %-3s %-24s location=%d%n", sc == 1 ? "in" : "out",
                            names.getOrDefault(e.getKey(), "#" + e.getKey()),
                            locations.getOrDefault(e.getKey(), -1));
                }
            }
        } finally {
            module.close();
        }
    }

    private static String str(int[] w, int start, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < start + count; i++) {
            for (int b = 0; b < 4; b++) {
                char c = (char) ((w[i] >> (8 * b)) & 0xFF);
                if (c == 0) return sb.toString();
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** MC's ShaderManager does this at load time; here it is done by hand for the offline repro. */
    private static String preprocess(String source, Path includeDir) throws Exception {
        Matcher m = Pattern.compile("#moj_import\\s*<([^>]+)>").matcher(source);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String importPath = m.group(1);
            // Imports are namespaced, and namespaces collide: Sodium and vanilla both ship a
            // globals.glsl and a fog.glsl with different contents. Resolve under a per-namespace
            // directory so a Sodium import cannot silently pick up the vanilla file.
            int colon = importPath.indexOf(':');
            String namespace = colon >= 0 ? importPath.substring(0, colon) : "minecraft";
            String location = colon >= 0 ? importPath.substring(colon + 1) : importPath;
            String file = location.substring(location.lastIndexOf('/') + 1);
            Path candidate = includeDir.resolve(namespace).resolve(file);
            if (!Files.isRegularFile(candidate)) {
                candidate = includeDir.resolve(file);
            }
            String include = Files.readString(candidate);
            include = include.replaceFirst("(?m)^\\s*#version[^\\n]*\\n", "");
            m.appendReplacement(out, Matcher.quoteReplacement(include));
        }
        m.appendTail(out);
        return out.toString();
    }
}
