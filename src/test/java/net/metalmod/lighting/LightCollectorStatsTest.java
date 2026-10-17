package net.metalmod.lighting;

import java.lang.reflect.Field;
import java.util.List;

/** Regression for suppressed extraction retaining plausible work from a previous frame (BUG-050). */
public final class LightCollectorStatsTest {
    public static int runTests() {
        try {
            for (boolean nonempty : new boolean[]{false, true}) {
                LightCollector.publish(new LightSnapshot(0, 0, 0, nonempty
                        ? List.of(new PointLight(0, 0, 0, 2, 1, 1, 1, 1)) : List.of()));
                seedWork();
                long before = LightCollector.generation();
                LightCollector.suppress();
                requireZeroWork();
                require(LightCollector.current().lights().isEmpty(), "suppression removes sources");
                require(LightCollector.generation() == before + (nonempty ? 1 : 0),
                        "suppression publishes removal only for a nonempty set");
                before = LightCollector.generation();
                seedWork();
                LightCollector.suppress();
                requireZeroWork();
                require(LightCollector.generation() == before, "repeated suppression does not force GPU uploads");
            }
            seedWork();
            LightCollector.clear();
            requireZeroWork();
            // A real invocation after suppression must report fresh work, even its detached-level
            // fast path. Present consumes the counters independently of immutable publication.
            LightCollector.extract(null, null, 0);
            require(LightCollector.extractNanos() > 0, "re-enabled extraction has a fresh duration");
            long generation = LightCollector.generation();
            LightCollector.resetWorkStats();
            requireZeroWork();
            require(LightCollector.generation() == generation, "consuming work does not republish sources");
            System.out.println("PASS light collector: empty/nonempty suppression, clear and fresh work attribution");
            return 0;
        } catch (ReflectiveOperationException | AssertionError error) {
            error.printStackTrace();
            return 1;
        } finally {
            LightCollector.clear();
        }
    }

    // Seed distinct measured values without a world, clock mocks or production-only test hooks.
    private static void seedWork() throws ReflectiveOperationException {
        for (String name : new String[]{"extractNanos", "entityQueryNanos", "blockIndexNanos"}) {
            Field field = LightCollector.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(null, 12345L);
        }
        for (String name : new String[]{"examined", "allocated"}) {
            Field field = LightCollector.class.getDeclaredField(name);
            field.setAccessible(true);
            field.setInt(null, 17);
        }
    }

    private static void requireZeroWork() {
        require(LightCollector.extractNanos() == 0 && LightCollector.entityQueryNanos() == 0
                && LightCollector.blockIndexNanos() == 0 && LightCollector.examined() == 0
                && LightCollector.allocated() == 0, "frames without extraction report zero work");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
