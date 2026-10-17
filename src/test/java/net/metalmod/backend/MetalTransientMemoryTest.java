package net.metalmod.backend;

import com.mojang.blaze3d.buffers.GpuBuffer;

/** Small blocks force spill/recycling without allocating hundreds of megabytes in a test. */
public final class MetalTransientMemoryTest {
    public static int runTests() {
        MetalDevice device = MetalDevice.create();
        if (device == null) throw new AssertionError("Transient test requires a Metal device");
        try (MetalTransientMemory arena = new MetalTransientMemory(device, 64)) {
            var first = arena.allocateGpuMapped(48, 16, GpuBuffer.USAGE_COPY_SRC);
            first.data().putInt(0, 17);
            var second = arena.allocateGpuMapped(48, 16, GpuBuffer.USAGE_COPY_SRC);
            second.data().putInt(0, 34);
            require(first.data().getInt(0) == 17, "spill must not overwrite an earlier allocation");
            require(((MetalBuffer) first.slice().buffer()).handle().address()
                    != ((MetalBuffer) second.slice().buffer()).handle().address(), "spill uses separate storage");
            var partial = arena.allocateGpu(48, 16, GpuBuffer.USAGE_COPY_SRC, 16, 4);
            require(partial.length() == 16, "minimum size/multiple contract uses the available tail");
            var large = arena.allocateGpu(96, 16, GpuBuffer.USAGE_COPY_SRC);
            require(large.length() == 96, "oversized requests use a separate block");
            var output = MetalNative.bufferCreate(device.deviceHandle(), 24 * 4);
            try {
                for (int i = 0; i < 24; ++i) {
                    var allocation = arena.allocateGpuMapped(48, 16, GpuBuffer.USAGE_COPY_SRC);
                    allocation.data().putInt(0, 100 + i);
                    var buffer = (MetalBuffer) allocation.slice().buffer();
                    MetalNative.copyBufferToBuffer(device.queueHandle(), buffer.handle(),
                            buffer.baseOffset() + allocation.slice().offset(), output, i * 4L, 4);
                    arena.endFrame();
                }
                MetalNative.queueSynchronize(device.queueHandle());
                var got = MetalNative.bufferContents(output, 24 * 4).asByteBuffer().order(java.nio.ByteOrder.nativeOrder());
                for (int i = 0; i < 24; ++i) require(got.getInt(i * 4) == 100 + i, "GPU readers survive arena recycling");
            } finally { MetalNative.bufferRelease(output); }
            boolean rejected = false;
            try { arena.allocateGpu(4, 3, GpuBuffer.USAGE_COPY_SRC); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "invalid alignment is rejected");
            rejected = false;
            try { arena.allocateGpu(Long.MAX_VALUE, 16, GpuBuffer.USAGE_COPY_SRC); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "oversized native allocation is rejected before entering Metal");
            System.out.println("PASS transient spill, oversized allocation, minimum size and fenced recycling");
            return 0;
        } finally { device.close(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
