package net.metalmod.upscaling;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import net.metalmod.backend.MetalNative;

/** Packaged Java/native ABI checks; actual display delivery lives in the native window harness. */
public final class DisplayLinkTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static int runTests() {
        try {
            require(MetalNative.isAvailable(), "packaged dylib available");
            for (String name : new String[]{"create", "healthy", "submit", "stop", "release", "stats"})
                require(SymbolLookup.loaderLookup().find("mmm_display_link_" + name).isPresent(), "packaged symbol " + name);
            var nil = MemorySegment.NULL;
            require(MetalNative.displayLinkCreate(nil,nil,64,48).address()==0, "NULL ownership denied");
            require(!MetalNative.displayLinkHealthy(nil), "NULL presenter unhealthy");
            require(MetalNative.displayLinkSubmit(nil,nil,nil,nil,1L,0.25f,0.5f,0.75f,1f)==-1,
                    "snapshot ABI pointers/uint64/floats");
            require(MetalNative.displayLinkStop(nil)==0, "NULL stop safe");
            MetalNative.displayLinkRelease(nil);
            require(MetalNative.displayLinkStats(nil).length==14, "statistics ABI length");
            System.out.println("PASS display-link packaged optional symbols and Panama ABI");
            return 0;
        } catch (RuntimeException | AssertionError error) {
            error.printStackTrace(); return 1;
        }
    }
}
