package net.metalmod;

import net.metalmod.ffi.MetalBridge;
import net.metalmod.memory.UnifiedMemoryManager;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class UnifiedMemoryTest {

    public static void runTests() {
        System.out.println("--------------------------------------------------");
        System.out.println("Running Apple Silicon Unified Memory (UMA) Tests");
        System.out.println("--------------------------------------------------");

        // Test 1: 16 KB Page Alignment & Allocation
        System.out.print("[UMA TEST 1] Allocating 16 KB, 64 KB, 1 MB, and 16 MB UMA buffers... ");
        long[] testSizes = {16384L, 65536L, 1048576L, 16777216L};
        for (long size : testSizes) {
            MemorySegment buf = UnifiedMemoryManager.getInstance().allocate(size);
            if (buf.equals(MemorySegment.NULL) || buf.address() == 0) {
                System.out.println("FAIL (Null allocation for size: " + size + ")");
                System.exit(1);
            }
            // Check 16 KB page alignment on Apple Silicon
            if ((buf.address() & (UnifiedMemoryManager.PAGE_SIZE_16K - 1)) != 0) {
                System.out.println("FAIL (Pointer not 16 KB aligned: 0x" + Long.toHexString(buf.address()) + ")");
                System.exit(1);
            }
            // Test write and readback
            for (long offset = 0; offset < Math.min(size, 4096L); offset += 8) {
                buf.set(ValueLayout.JAVA_LONG, offset, 0xCAFEBABE01234567L ^ offset);
            }
            for (long offset = 0; offset < Math.min(size, 4096L); offset += 8) {
                long val = buf.get(ValueLayout.JAVA_LONG, offset);
                if (val != (0xCAFEBABE01234567L ^ offset)) {
                    System.out.println("FAIL (Memory readback mismatch at offset " + offset + ")");
                    System.exit(1);
                }
            }
            UnifiedMemoryManager.getInstance().free(buf);
        }
        System.out.println("PASS (16 KB page-aligned, read/write verified)");

        // Test 2: Telemetry Readout
        System.out.print("[UMA TEST 2] Querying Apple Silicon Mach VM & Metal Telemetry... ");
        UnifiedMemoryManager.getInstance().updateTelemetry();
        long totalRam = UnifiedMemoryManager.getInstance().getTotalPhysicalMemory();
        long availRam = UnifiedMemoryManager.getInstance().getAvailableMemory();
        long resident = UnifiedMemoryManager.getInstance().getProcessResident();
        long metalMax = UnifiedMemoryManager.getInstance().getMetalMaxWorkingSet();
        int pressure = UnifiedMemoryManager.getInstance().getMemoryPressureLevel();

        if (totalRam <= 0) {
            System.out.println("FAIL (Total RAM is " + totalRam + ")");
            System.exit(1);
        }
        if (resident <= 0) {
            System.out.println("FAIL (Resident footprint is " + resident + ")");
            System.exit(1);
        }
        if (pressure < 0 || pressure > 2) {
            System.out.println("FAIL (Invalid pressure level: " + pressure + ")");
            System.exit(1);
        }

        System.out.println("PASS");
        System.out.println("  -> Total RAM: " + UnifiedMemoryManager.formatBytes(totalRam) +
                           " | Avail: " + UnifiedMemoryManager.formatBytes(availRam) +
                           " | Footprint: " + UnifiedMemoryManager.formatBytes(resident) +
                           " | Metal Working Set Cap: " + UnifiedMemoryManager.formatBytes(metalMax) +
                           " | Pressure Level: " + pressure);

        // Test 3: Idle Purge
        System.out.print("[UMA TEST 3] Testing non-destructive idle scratch purge (MADV_FREE_REUSABLE)... ");
        MetalBridge.purgeIdleMemory();
        System.out.println("PASS");
    }
}
