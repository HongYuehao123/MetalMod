package net.metalmod.benchmark;

import java.lang.foreign.MemorySegment;
import java.nio.file.*;
import java.util.ArrayDeque;
import net.metalmod.backend.*;

/** Disposable benchmark only. Same completion fence and two-frame bound in both modes. */
public final class OffscreenCompletion {
    public static final boolean ENABLED=Boolean.getBoolean("metalmod.offscreenComparison");
    public static boolean offscreen;
    private record Pending(MemorySegment fence, boolean measured) {}
    private static final ArrayDeque<Pending> pending=new ArrayDeque<>();
    private static boolean started, measuring;
    private static long start, elapsed, submitted, completed;
    public static boolean started() {return started;}
    public static void mode(boolean value) {drain();offscreen=value;started=false;measuring=false;}
    public static void begin() {drain();submitted=completed=0;start=System.nanoTime();started=measuring=true;}
    public static void finish() {drain();elapsed=System.nanoTime()-start;measuring=false;}
    public static void beforeFrame() {if(pending.size()>=2) retire();}
    public static void frame(MetalDevice device) {
        // The fence creation flushes all preceding rendering/utility work on the render queue.
        if(pending.size()>=2) throw new IllegalStateException("GPU frame bound exceeded");
        var fence=MetalNative.fenceCreate(device.queueHandle());
        if(fence.address()==0) throw new IllegalStateException("Benchmark completion fence creation failed");
        pending.add(new Pending(fence,measuring));if(measuring)submitted++;
    }
    private static void retire() {
        var frame=pending.remove();
        try {
            if(!MetalNative.fenceWait(frame.fence(),10_000_000_000L))
                throw new IllegalStateException("Benchmark GPU completion timeout");
            if(frame.measured())completed++;
        } finally {MetalNative.fenceRelease(frame.fence());}
    }
    public static void drain() {while(!pending.isEmpty())retire();}
    public static void save(Path folder) throws Exception {
        if(!started || measuring || submitted!=completed || elapsed<=0)
            throw new IllegalStateException("Incomplete GPU measurement");
        Files.writeString(folder.resolve("gpu-completion.json"),
            "{\"offscreen\":"+offscreen+",\"max_in_flight\":2,\"submitted\":"+submitted+
            ",\"completed\":"+completed+",\"elapsed_ns\":"+elapsed+
            ",\"completed_fps\":"+(completed*1e9/elapsed)+"}\n");
    }
}
