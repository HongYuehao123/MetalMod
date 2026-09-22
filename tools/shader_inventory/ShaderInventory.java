import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalRenderPipeline;
import net.metalmod.backend.MetalShaderCompiler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compile <em>every</em> vanilla render pipeline through MetalMod's real shader path, outside the
 * game, and report a list instead of an absence of errors.
 *
 * <p>Phase 4's exit criterion is "unmodified vanilla shaders compile and run". The game only proves
 * the pipelines the engine announces at startup; {@code RenderPipelines} declares far more and the
 * rest are compiled lazily, so an unexercised pipeline is silently unverified. This walks every
 * pipeline field and compiles it.
 *
 * <p>Fidelity comes from using the engine's own pieces rather than reimplementing them:
 * {@code RenderPipelines}' static fields are the real definitions, {@link GlslPreprocessor} is the
 * real preprocessor (imports and version handling), {@code injectDefines} is the real define
 * injection, and {@link MetalRenderPipeline#create} is the real GLSL -> SPIR-V -> MSL -> pipeline
 * path. Only reading the shader text out of the client jar is reproduced here.
 *
 * <p>Usage: tools/shader_inventory/run.sh [instance-dir]
 * <br>Exit code is the number of pipelines that failed, capped at 125.
 */
public final class ShaderInventory {

    private static final String SHADER_ROOT = "assets/minecraft/shaders/";

    /** How a pipeline ended up. */
    private enum Status { OK, FAILED, NO_SOURCE }

    private record Result(String location, Status status, String detail) {
    }

    public static void main(String[] args) throws Exception {
        Path instance = args.length > 0 ? Paths.get(args[0]) : defaultInstance();
        Path jar = instance.resolve(instance.getFileName() + ".jar");
        if (!java.nio.file.Files.isRegularFile(jar)) {
            System.err.println("ERROR: client jar not found: " + jar);
            System.exit(2);
        }
        System.out.println("Shader inventory");
        System.out.println("  instance : " + instance);
        System.out.println("  jar      : " + jar);

        List<RenderPipeline> pipelines = vanillaPipelines();
        System.out.println("  pipelines: " + pipelines.size());
        System.out.println();

        List<Result> results = new ArrayList<>();
        MetalDevice device = MetalDevice.create();
        if (device == null) {
            System.err.println("ERROR: could not create a Metal device");
            System.exit(2);
        }
        try (ZipFile zip = new ZipFile(jar.toFile());
             MetalShaderCompiler compiler = new MetalShaderCompiler()) {

            Map<String, String> shaderCache = new LinkedHashMap<>();
            for (RenderPipeline pipeline : pipelines) {
                results.add(compileOne(device, compiler, zip, shaderCache, pipeline));
            }
            // The 87 pipelines share shader pairs, so the pair cache must report reuse here. This is
            // how the cache is verified without a game run.
            System.out.println("cache: " + compiler.cacheSummary());
            System.out.println();
            device.close();
        }

        report(results);
    }

    private static Result compileOne(MetalDevice device, MetalShaderCompiler compiler, ZipFile zip,
                                     Map<String, String> shaderCache, RenderPipeline pipeline) {
        String location = pipeline.getLocation().toString();
        String vertexPath = shaderPath(pipeline.getVertexShader().toString(), ShaderType.VERTEX);
        String fragmentPath = shaderPath(pipeline.getFragmentShader().toString(), ShaderType.FRAGMENT);
        if (!hasEntry(zip, vertexPath) || !hasEntry(zip, fragmentPath)) {
            String missing = !hasEntry(zip, vertexPath) ? vertexPath : fragmentPath;
            return new Result(location, Status.NO_SOURCE, "not in jar: " + missing);
        }

        ShaderSource source = new ShaderSource() {
            @Override
            public String get(net.minecraft.resources.Identifier id, ShaderType type) {
                String path = shaderPath(id.toString(), type);
                return shaderCache.computeIfAbsent(path, p -> preprocess(zip, p));
            }
        };

        // MetalRenderPipeline.create reports why it failed on System.err; capture it so each failure
        // can be attributed to its pipeline instead of interleaving with the progress output.
        PrintStream realErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        MetalRenderPipeline compiled;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            compiled = MetalRenderPipeline.create(device, compiler, pipeline, source);
        } finally {
            System.setErr(realErr);
        }
        if (compiled != null) {
            compiled.close();
            return new Result(location, Status.OK, "");
        }
        String detail = captured.toString(StandardCharsets.UTF_8).strip();
        int marker = detail.indexOf("[MetalMod]");
        if (marker > 0) {
            detail = detail.substring(marker);
        }
        return new Result(location, Status.FAILED, detail.isEmpty() ? "(no message)" : detail);
    }

    private static void report(List<Result> results) {
        List<Result> failed = new ArrayList<>();
        List<Result> noSource = new ArrayList<>();
        for (Result result : results) {
            switch (result.status()) {
                case OK -> { }
                case FAILED -> failed.add(result);
                case NO_SOURCE -> noSource.add(result);
            }
        }

        System.out.println("=========== results ===========");
        for (Result result : results) {
            if (result.status() != Status.OK) {
                System.out.printf("%-7s %s%n", result.status(), result.location());
                System.out.println("        " + result.detail());
            }
        }
        System.out.println();
        System.out.printf("total=%d ok=%d failed=%d no-source=%d%n",
                results.size(), results.size() - failed.size() - noSource.size(),
                failed.size(), noSource.size());
        if (!noSource.isEmpty()) {
            System.out.println("no-source pipelines are announced by the engine without a shader to");
            System.out.println("compile (the post-processing chain); the game skips them too.");
        }
        System.exit(Math.min(failed.size(), 125));
    }

    /** Every RenderPipeline declared by the engine, in a stable order. */
    private static List<RenderPipeline> vanillaPipelines() throws Exception {
        Class<?> pipelines = Class.forName("net.minecraft.client.renderer.RenderPipelines");
        Map<String, RenderPipeline> byField = new TreeMap<>();
        for (Field field : pipelines.getDeclaredFields()) {
            if (!RenderPipeline.class.isAssignableFrom(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            Object value = field.get(null);
            if (value instanceof RenderPipeline pipeline) {
                byField.put(field.getName(), pipeline);
            }
        }
        return new ArrayList<>(byField.values());
    }

    /** {@code minecraft:core/terrain} + VERTEX -> {@code assets/minecraft/shaders/core/terrain.vsh} */
    private static String shaderPath(String identifier, ShaderType type) {
        String namespace = "minecraft";
        String path = identifier;
        int colon = identifier.indexOf(':');
        if (colon >= 0) {
            namespace = identifier.substring(0, colon);
            path = identifier.substring(colon + 1);
        }
        String extension = type == ShaderType.VERTEX ? ".vsh" : ".fsh";
        return "assets/" + namespace + "/shaders/" + path + extension;
    }

    private static boolean hasEntry(ZipFile zip, String path) {
        return zip.getEntry(path) != null;
    }

    /** Reads a shader out of the jar and runs the engine's preprocessor over it (imports, version). */
    private static String preprocess(ZipFile zip, String path) {
        String raw = readEntry(zip, path);
        if (raw == null) {
            return "";
        }
        // The engine's own preprocessor (ShaderManager$1) keeps an importedLocations set and returns
        // null for an import it has already inlined in this file. That matters: vanilla's
        // rendertype_end_portal.vsh imports projection.glsl twice, and inlining it twice makes
        // glslang reject the shader with "'Projection': Cannot reuse block name". Reproduce the
        // dedup, or the inventory invents failures the game does not have.
        java.util.Set<String> importedLocations = new java.util.HashSet<>();
        GlslPreprocessor preprocessor = new GlslPreprocessor() {
            @Override
            public String applyImport(boolean isRelative, String importPath) {
                String resolved = isRelative ? resolveRelative(path, importPath) : importPath;
                // Matches Identifier.parse(path).withPrefix("shaders/include/").
                int colon = resolved.indexOf(':');
                String namespace = colon >= 0 ? resolved.substring(0, colon) : "minecraft";
                String location = colon >= 0 ? resolved.substring(colon + 1) : resolved;
                String key = namespace + ":shaders/include/" + location;
                if (!importedLocations.add(key)) {
                    return null;
                }
                String text = readEntry(zip, SHADER_ROOT + "include/" + stripIncludeDir(location));
                if (text == null) {
                    System.err.println("  (missing include " + importPath + ")");
                    return "";
                }
                return text;
            }
        };
        return String.join("\n", preprocessor.process(raw));
    }

    private static String stripIncludeDir(String resolved) {
        // 'minecraft/animation_sprite.glsl' or 'minecraft/include/animation_sprite.glsl'
        int slash = resolved.lastIndexOf('/');
        return slash >= 0 ? resolved.substring(slash + 1) : resolved;
    }

    private static String resolveRelative(String from, String importPath) {
        int slash = from.lastIndexOf('/');
        String dir = slash >= 0 ? from.substring(0, slash + 1) : "";
        return dir + importPath;
    }

    private static String readEntry(ZipFile zip, String path) {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private static Path defaultInstance() {
        String override = System.getenv("METALMOD_MC_INSTANCE");
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        return Paths.get(System.getProperty("user.home"),
                "Documents/.minecraft/versions/MetalMod_Test_26.2");
    }
}
