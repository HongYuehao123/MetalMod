package net.metalmod.debug;

import java.nio.file.Files;

/** Capture lifecycle, bounded attribution and CSV escaping without requiring a game session. */
public final class ResourceTelemetryTest {
    public static int runTests() {
        try {
            ResourceTelemetry.start(false);
            require(ResourceTelemetry.begin() == 0, "disabled path has no timer");
            ResourceTelemetry.end(0, "ignored", "ignored", "buffer", 99);
            require(ResourceTelemetry.stop().rows().isEmpty(), "disabled path records nothing");
            ResourceTelemetry.start(true);
            long stale = ResourceTelemetry.begin();
            ResourceTelemetry.start(true);
            ResourceTelemetry.end(stale, "stale", "previous capture", "buffer", 10);
            long timer = ResourceTelemetry.begin();
            ResourceTelemetry.end(timer, "texture.texel-emulation", "cloud, \"data\"\nrow", "R8_SINT", 123);
            ResourceTelemetry.end(timer, "texture.texel-emulation", "cloud, \"data\"\nrow", "R8_SINT", 321);
            ResourceTelemetry.attachment(timer, "pass.attachment-clear.full", "lightmap", "RGBA8", 1024);
            var snapshot = ResourceTelemetry.stop();
            require(snapshot.enabled() && snapshot.rows().size() == 2, "capture isolation and distinct operations");
            var cloud = snapshot.rows().getFirst();
            require(cloud.calls() == 2 && cloud.bytes() == 444 && cloud.apiNanos() >= 0, "exact payload aggregation");
            require(snapshot.rows().get(1).apiNanos() == 0, "attachment declarations do not duplicate API time");
            ResourceTelemetry.end(timer, "late", "late", "buffer", 100);
            require(ResourceTelemetry.stop().rows().isEmpty(), "stopped capture ignores delayed operations");
            var directory = Files.createTempDirectory("metalmod-resource-telemetry-");
            try {
                snapshot.write(directory);
                String csv = Files.readString(directory.resolve("resources.csv"));
                require(csv.contains("\"cloud, \"\"data\"\"\nrow\",\"R8_SINT\",2,444,"), "CSV quotes labels safely");
                require(Files.readString(directory.resolve("INSTRUMENTED.txt")).contains("not GPU duration"), "instrumented export is marked");
            } finally {
                Files.deleteIfExists(directory.resolve("resources.csv"));
                Files.deleteIfExists(directory.resolve("INSTRUMENTED.txt"));
                Files.delete(directory);
            }
            ResourceTelemetry.start(true);
            for (int i = 0; i < 600; i++)
                ResourceTelemetry.end(ResourceTelemetry.begin(), "buffer.write", "resource-" + i, "buffer", 1);
            var bounded = ResourceTelemetry.stop();
            require(bounded.rows().size() == 513, "bounded keys plus overflow bucket");
            require(bounded.rows().stream().mapToLong(ResourceTelemetry.Row::calls).sum() == 600, "overflow preserves calls");
            require(bounded.rows().stream().mapToLong(ResourceTelemetry.Row::bytes).sum() == 600, "overflow preserves bytes");
            require(snapshot.rows().getFirst().bytes() == 444, "export snapshot does not change in later captures");
            System.out.println("PASS resource telemetry: lifecycle, exact bytes, bounded keys, immutable export and CSV");
            return 0;
        } catch (Exception | AssertionError failure) {
            failure.printStackTrace();
            return 1;
        } finally {
            ResourceTelemetry.stop();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
