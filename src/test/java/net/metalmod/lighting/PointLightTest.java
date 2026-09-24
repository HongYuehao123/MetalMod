package net.metalmod.lighting;

import java.nio.ByteOrder;

public final class PointLightTest {
    public static int runTests() {
        try {
            var data = new PointLight(30_000_000.375, -40.125, -30_000_000.625,
                    8, 1, 0.5f, 0.25f, 0.75f).relativeTo(30_000_000.25, -40, -30_000_000.5);
            require(data.remaining() == 32 && data.order() == ByteOrder.nativeOrder(), "std140 size/order");
            require(data.getFloat(0) == 0.125f && data.getFloat(4) == -0.125f
                    && data.getFloat(8) == -0.125f, "subtract camera before float conversion");
            require(data.getFloat(12) == 8 && data.getFloat(16) == 1 && data.getFloat(20) == 0.5f
                    && data.getFloat(24) == 0.25f && data.getFloat(28) == 0.75f, "std140 field offsets");
            rejected(() -> new PointLight(Double.NaN, 0, 0, 8, 1, 1, 1, 1));
            rejected(() -> new PointLight(0, 0, 0, 0, 1, 1, 1, 1));
            rejected(() -> new PointLight(0, 0, 0, Float.POSITIVE_INFINITY, 1, 1, 1, 1));
            rejected(() -> new PointLight(0, 0, 0, 8, -1, 1, 1, 1));
            rejected(() -> new PointLight(0, 0, 0, 8, 1, 1, 1, Float.NaN));
            rejected(() -> new PointLight(0, 0, 0, 8, 1, 1, 1, 2));
            rejected(() -> new PointLight(0, 0, 0, 8, 1, 1, 1, 1).relativeTo(Double.NaN, 0, 0));
            rejected(() -> new PointLight(Double.MAX_VALUE, 0, 0, 8, 1, 1, 1, 1).relativeTo(0, 0, 0));
            var snapshot = new LightSnapshot(10, 20, 30, java.util.List.of(
                    new PointLight(11, 22, 33, 4, 1, 0.5f, 0.25f, 0.75f)));
            var packet = snapshot.encode();
            require(packet.remaining() == LightSnapshot.BYTES && packet.getInt(0) == 1
                    && packet.getInt(4) == LightSnapshot.ABI_VERSION, "light set count and ABI version");
            // Header 16 bytes, then 8 floats per record: position.xyz, radius, rgb, intensity.
            require(packet.getFloat(16) == 1 && packet.getFloat(20) == 2
                    && packet.getFloat(24) == 3 && packet.getFloat(28) == 4,
                    "snapshot coordinates relative to its camera");
            require(packet.getFloat(32) == 1 && packet.getFloat(36) == 0.5f
                    && packet.getFloat(40) == 0.25f && packet.getFloat(44) == 0.75f,
                    "record colour and intensity offsets");
            // The second record starts one stride further on: this is what a shared GPU layout means.
            var two = new LightSnapshot(10, 20, 30, java.util.List.of(
                    new PointLight(11, 22, 33, 4, 1, 0.5f, 0.25f, 0.75f),
                    new PointLight(12, 23, 34, 5, 0.25f, 0.5f, 1, 0.5f))).encode();
            // Every record is camera-relative, so record 2's x is 12 - 10 = 2 and its radius is 5.
            require(two.getInt(0) == 2 && two.getFloat(48) == 2 && two.getFloat(60) == 5,
                    "second record starts at 16 + 32 and is camera-relative too");
            rejected(() -> new LightSnapshot(0, 0, 0, java.util.Collections.nCopies(
                    LightSnapshot.CAPACITY + 1, new PointLight(0, 0, 0, 1, 1, 1, 1, 1))));
            // The published bound, pinned here so a change to it is a decision rather than a slip, and
            // so the derived sizes that follow from it are checked to stay in range.
            require(LightSnapshot.CAPACITY == 64, "published light capacity");
            require(LightSnapshot.BYTES == 16 + LightSnapshot.CAPACITY * 32,
                    "the light set buffer follows the capacity");
            require(LightClusterGrid.recordTexel(LightSnapshot.CAPACITY - 1) + 2
                            <= LightClusterGrid.TEXELS,
                    "the cluster table has room for a full light set");
            require(LightClusterGrid.TEXELS <= 16384, "the cluster table stays one texture row");
            require(LightClusterGrid.ENTRIES_PER_CELL == 16, "published per-cell bound");
            System.out.println("PASS point-light ABI, large origins and invalid input rejection");
            return 0;
        } catch (AssertionError error) {
            error.printStackTrace();
            return 1;
        }
    }

    private static void rejected(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid light/camera accepted");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
