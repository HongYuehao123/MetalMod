package net.metalmod.metalfx;

/** Standalone checks for the presentation based Temporal fallback threshold. */
public final class TemporalPacingGuardTest {
    private TemporalPacingGuardTest() {}

    public static int runTests() {
        int failures = 0;
        TemporalPacingGuard guard = new TemporalPacingGuard();
        long start = 1_000_000_000L;
        if (guard.observe(start, 100, 5)) failures++;
        if (guard.observe(start + 10_000_000_000L, 700, 8)) failures++;
        if (guard.observe(start + 20_000_000_000L, 1300, 12)) failures++;
        if (!guard.observe(start + 30_000_000_000L, 1850, 42)) failures++;
        if (!guard.observe(start + 31_000_000_000L, 1910, 42)) failures++;
        if (guard.observeSpatial(start + 40_000_000_000L, 2450, 44)) failures++;
        if (!guard.fallback()) failures++;
        if (!guard.observeSpatial(start + 50_000_000_000L, 3000, 80)) failures++;
        if (guard.fallback() || guard.observe(start + 60_000_000_000L, 3600, 130)) failures++;
        guard.reset();
        if (guard.fallback() || guard.observe(start + 32_000_000_000L, 1950, 42)) failures++;
        System.out.println("TemporalPacingGuardTest: " + (failures == 0 ? "PASS" : "FAIL " + failures));
        return failures;
    }
}
