package net.metalmod.debug;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where capture routes live, and which one a capture should use.
 *
 * <p>A route is named after the dimension it was recorded in, so the workflow is: go to the
 * dimension, record the route once, then press F8 as often as you like. The dimension and the
 * backend are the only things that change between runs. {@code -Dmetalmod.routeName=<name>}
 * overrides the name when more than one route is wanted for the same dimension.
 */
public final class CaptureRouteStore {

    private static final String NAME_PROPERTY = "metalmod.routeName";
    private static final String SAMPLE_PROPERTY = "metalmod.routeSampleMs";
    private static final int DEFAULT_SAMPLE_MS = 500;

    /** How often the recorder samples position. Read once; it is a launch setting like the others. */
    public static final int SAMPLE_MS = readSampleMs();

    private CaptureRouteStore() {
    }

    private static int readSampleMs() {
        try {
            int value = Integer.getInteger(SAMPLE_PROPERTY, DEFAULT_SAMPLE_MS);
            return Math.max(100, Math.min(5000, value));
        } catch (NumberFormatException error) {
            return DEFAULT_SAMPLE_MS;
        }
    }

    public static Path directory(Minecraft minecraft) {
        return minecraft.gameDirectory.toPath().resolve("debug/metalmod/routes");
    }

    public static Path path(Minecraft minecraft, String name) {
        return directory(minecraft).resolve(CaptureRoute.sanitize(name) + ".json");
    }

    /** The dimension the player is in, or null with no world loaded. */
    public static String dimension(Minecraft minecraft) {
        return minecraft.level == null ? null : minecraft.level.dimension().identifier().toString();
    }

    /** The route name for the current dimension, unless overridden on the command line. */
    public static String name(Minecraft minecraft) {
        String override = System.getProperty(NAME_PROPERTY);
        if (override != null && !override.isBlank()) {
            return CaptureRoute.sanitize(override);
        }
        String dimension = dimension(minecraft);
        return CaptureRoute.sanitize(dimension == null ? "route" : dimension.replace(':', '.'));
    }

    /** The route a capture should replay, or null when none is recorded for this dimension. */
    public static CaptureRoute load(Minecraft minecraft) {
        Path file = path(minecraft, name(minecraft));
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return CaptureRoute.load(file);
        } catch (IOException error) {
            System.err.println("[MetalMod] could not read " + file + ": " + error.getMessage());
            return null;
        }
    }

    /** Save a recorded route, returning the file it went to. */
    public static Path save(Minecraft minecraft, CaptureRoute route, int sampleMs,
                            double mergeRadius) throws IOException {
        Path file = path(minecraft, route.name());
        route.save(file, java.time.LocalDateTime.now().withNano(0).toString(),
                minecraft.getLaunchedVersion(), sampleMs, mergeRadius);
        return file;
    }

    /** Every recorded route, for the log line that says what is available. */
    public static String describeAvailable(Minecraft minecraft) {
        Path directory = directory(minecraft);
        if (!Files.isDirectory(directory)) {
            return "none recorded";
        }
        try (var entries = Files.list(directory)) {
            String names = entries.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replace(".json", ""))
                    .sorted()
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("none recorded");
            return names;
        } catch (IOException error) {
            return "unreadable: " + error.getMessage();
        }
    }
}
