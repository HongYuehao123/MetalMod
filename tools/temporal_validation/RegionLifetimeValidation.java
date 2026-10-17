package net.metalmod.validation;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Bounded test-only retention lets the final gate inspect actual completed vanilla tasks. */
public final class RegionLifetimeValidation {
    private static final AtomicInteger offered = new AtomicInteger();
    private static final ConcurrentLinkedQueue<BooleanSupplier> samples = new ConcurrentLinkedQueue<>();
    private RegionLifetimeValidation() {}
    public static void sample(BooleanSupplier released) {
        if (offered.getAndIncrement() < 128) samples.add(released);
    }
    public static String result() {
        int count = 0, retained = 0;
        for (var sample : samples) { count++; if (!sample.getAsBoolean()) retained++; }
        return "samples=" + count + " retained=" + retained;
    }
    public static boolean passed() {
        if (samples.isEmpty()) return false;
        return Boolean.parseBoolean(System.getProperty("metalmod.compileRegionDrop", "true"))
                ? samples.stream().allMatch(BooleanSupplier::getAsBoolean)
                : samples.stream().anyMatch(sample -> !sample.getAsBoolean());
    }
}
