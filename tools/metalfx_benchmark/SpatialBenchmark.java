package net.metalmod.benchmark;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.InactivityFpsLimit;
import com.mojang.blaze3d.platform.NativeImage;
import net.metalmod.debug.*;
import net.metalmod.upscaling.*;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.GpuSurface;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Test add-on: install only in a disposable clone; capture preparation modifies the world. */
public final class SpatialBenchmark implements ClientModInitializer {
    private record Run(String name, boolean sr, int strength, boolean aa, boolean vsync) {}
    private final List<Run> runs=List.of(
        new Run("01-native",false,0,true,false),
        new Run("02-sr25-aa",true,25,true,false),
        new Run("03-sr33-aa",true,33,true,false),
        new Run("04-sr50-aa",true,50,true,false),
        new Run("05-sr50-aa",true,50,true,false),
        new Run("06-sr33-aa",true,33,true,false),
        new Run("07-sr25-aa",true,25,true,false),
        new Run("08-native",false,0,true,false),
        new Run("09-sr25-noaa",true,25,false,false),
        new Run("10-sr25-noaa",true,25,false,false),
        new Run("11-native-vsync",false,0,true,true),
        new Run("12-sr25-aa-vsync",true,25,true,true));
    private long started, deadline;
    private int index=-1, state=0;
    private CaptureRoute route;
    private CaptureRoutePlayer warmRoute;
    private Set<Path> previous=Set.of();
    private Path root;
    private boolean screenshot;
    private int focusRetries;
    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("metalmod.benchmark")) return;
        if (!Files.exists(Minecraft.getInstance().gameDirectory.toPath().resolve(".metalmod-benchmark-copy")))
            throw new IllegalStateException("Benchmark requires a disposable clone marker: .metalmod-benchmark-copy");
        var executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"SpatialBenchmark-control");t.setDaemon(true);return t;});
        executor.scheduleAtFixedRate(()->Minecraft.getInstance().execute(()->{
            try { tick(Minecraft.getInstance()); }
            catch(Throwable error) { error.printStackTrace(); state=99; }
        }),1,250,TimeUnit.MILLISECONDS);
    }
    private Set<Path> captures() throws Exception {
        try(var paths=Files.list(root)) {
            return new HashSet<>(paths.filter(Files::isDirectory).filter(p->Files.exists(p.resolve("summary.txt"))).toList());
        }
    }
    private void tick(Minecraft mc) throws Exception {
        if(state==99 || mc.level==null || mc.player==null || !mc.isGameLoadFinished()) return;
        long now=System.nanoTime();
        if(state==0) {
            root=mc.gameDirectory.toPath().resolve("debug/metalmod"); Files.createDirectories(root);
            mc.options.pauseOnLostFocus=false;
            mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
            mc.options.framerateLimit().set(260);
            mc.options.enableVsync().set(false);
            mc.gui.setScreen(null);
            GLFW.glfwFocusWindow(mc.getWindow().handle());
            route=CaptureRouteStore.load(mc);
            if(route==null || route.isEmpty()) throw new IllegalStateException("missing benchmark route");
            CaptureRoutePlayer.prepare(mc);
            warmRoute=new CaptureRoutePlayer(route); warmRoute.arm(mc); warmRoute.startAt(now);
            deadline=now+(route.totalDwellMs()+30_000)*1_000_000L;
            state=1;
            System.out.println("[SpatialBenchmark] initial full-route warmup, world="+mc.getSingleplayerServer().getWorldData().getLevelName());
            return;
        }
        if(state==1) {
            warmRoute.tick(mc,now);
            if(now>=deadline) next(mc,now);
            return;
        }
        if(state==2) {
            if(!screenshot && now>=deadline-2_000_000_000L) {
                Run run=runs.get(index);
                var field=mc.gameRenderer.getClass().getDeclaredField("mainRenderTarget");field.setAccessible(true);
                Screenshot.takeScreenshot((RenderTarget)field.get(mc.gameRenderer),image->{
                    // Vanilla screenshot export flips readback rows for a bottom-up backend.
                    // Restore native Metal orientation for this comparison artifact only.
                    try(image; var upright=new NativeImage(image.getWidth(),image.getHeight(),false)) {
                        image.copyRect(upright,0,0,0,0,image.getWidth(),image.getHeight(),false,true);
                        Path path=mc.gameDirectory.toPath().resolve("screenshots/"+run.name()+".png");
                        Files.createDirectories(path.getParent());upright.writeToFile(path);
                        System.out.println("[SpatialBenchmark] screenshot "+run.name());
                    } catch(Exception error) { throw new RuntimeException(error); }
                });
                screenshot=true;
            }
            if(now>=deadline) {
                GLFW.glfwFocusWindow(mc.getWindow().handle());
                var surfaceField=mc.getClass().getDeclaredField("windowSurface");surfaceField.setAccessible(true);
                var configuration=((GpuSurface)surfaceField.get(mc)).currentConfiguration().orElseThrow();
                var expected=runs.get(index).vsync() ? GpuSurface.PresentMode.FIFO : GpuSurface.PresentMode.IMMEDIATE;
                if(configuration.presentMode()!=expected) throw new IllegalStateException("Surface mode mismatch: "+configuration);
                System.out.println("[SpatialBenchmark] effective surface "+configuration);
                previous=captures(); PerformanceCapture.toggle(mc);
                if(!PerformanceCapture.isRecording()) throw new IllegalStateException("F8 capture did not start: "+PerformanceCapture.status());
                started=now;state=3;
                System.out.println("[SpatialBenchmark] capture started "+runs.get(index).name()+" "+MetalFxCoordinator.stats().summary());
            }
            return;
        }
        if(state==3) {
            if(PerformanceCapture.isRecording()) {
                if(now-started>90_000_000_000L) throw new IllegalStateException("capture timeout");
                return;
            }
            Set<Path> current=captures(); current.removeAll(previous);
            if(current.isEmpty()) {
                if(PerformanceCapture.status().contains("failed")) throw new IllegalStateException(PerformanceCapture.status());
                return; // asynchronous export
            }
            if(current.size()!=1) throw new IllegalStateException("ambiguous capture export");
            Run run=runs.get(index);Path capture=current.iterator().next();
            long badFocus;
            try(var lines=Files.lines(capture.resolve("frames.csv"))) {
                badFocus=lines.skip(1).filter(line->!line.split(",",7)[5].equals("1")).count();
            }
            if(badFocus>0) {
                Files.writeString(capture.resolve("REJECTED.txt"),"Lost focus in "+badFocus+" frames\n");
                System.out.println("[SpatialBenchmark] REJECTED focus loss; retry "+run.name());
                if (++focusRetries >= 3) throw new IllegalStateException("Three focus failures; keep the isolated game in front");
                index--; next(mc,now); return;
            }
            focusRetries=0;
            Files.writeString(capture.resolve("benchmark-run.txt"),run.toString()+"\n"+System.getProperty("metalmod.fxAntialias")+"\n");
            Files.writeString(root.resolve("benchmark-index.tsv"),run.name()+"\t"+capture.getFileName()+"\t"+run+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            System.out.println("[SpatialBenchmark] capture complete "+run.name()+" -> "+capture.getFileName());
            next(mc,now);
        }
    }
    private void next(Minecraft mc,long now) throws Exception {
        index++;
        if(index>=runs.size()) {
            Files.writeString(root.resolve("benchmark-complete.txt"),"All "+runs.size()+" captures exported\n");
            System.out.println("[SpatialBenchmark] COMPLETE");state=99;mc.stop();return;
        }
        Run run=runs.get(index);
        System.setProperty("metalmod.fxAntialias",Boolean.toString(run.aa()));
        UpscalingSettings.chooseEnabled(run.sr());UpscalingSettings.chooseStrength(run.strength());
        mc.options.enableVsync().set(run.vsync());
        GLFW.glfwFocusWindow(mc.getWindow().handle());
        new CaptureRoutePlayer(route).arm(mc);
        deadline=now+20_000_000_000L; screenshot=false;state=2;
        System.out.println("[SpatialBenchmark] warmup "+run);
    }
}
