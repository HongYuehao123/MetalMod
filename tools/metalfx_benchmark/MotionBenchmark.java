package net.metalmod.benchmark;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.InactivityFpsLimit;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuSurface;
import net.metalmod.debug.*;
import net.metalmod.upscaling.*;
import org.lwjgl.glfw.GLFW;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Disposable-world test only. Smooth pose is evaluated before every camera extraction. */
public final class MotionBenchmark implements ClientModInitializer {
    private record Run(String name,int strength,boolean vsync) {}
    private final boolean submissionCheck=Boolean.getBoolean("metalmod.submissionBenchmark");
    private final boolean staticCamera=Boolean.getBoolean("metalmod.benchmarkStaticCamera");
    private final String rendererComparison=System.getProperty("metalmod.rendererComparison", "");
    private final boolean nativeOnly=Boolean.getBoolean("metalmod.benchmarkNativeOnly");
    private final boolean offscreenComparison=Boolean.getBoolean("metalmod.offscreenComparison");
    private final List<Run> requestedRuns=offscreenComparison
        ? List.of(new Run("present01",0,false),new Run("offscreen02",0,false),new Run("offscreen03",0,false),new Run("present04",0,false))
        : !rendererComparison.isEmpty()
        ? List.of(new Run(rendererComparison+"-native",0,false),new Run(rendererComparison+"-sr25",25,false))
        : Boolean.getBoolean("metalmod.benchmarkDiagnostic")
        ? List.of(new Run("diagnostic-native-on",0,false),new Run("diagnostic-sr25-on",25,false))
        : submissionCheck
        ? List.of(new Run("batch01-native-off",0,false),new Run("batch02-native-on",0,false),
          new Run("batch03-native-on",0,false),new Run("batch04-native-off",0,false),
          new Run("batch05-sr25-off",25,false),new Run("batch06-sr25-on",25,false))
        : Boolean.getBoolean("metalmod.displayCheckOnly")
        ? List.of(new Run("display01-immediate",0,false),new Run("display02-fifo",0,true),new Run("display03-sr25-fifo",25,true)) : List.of(new Run("motion01-native",0,false),new Run("motion02-sr25",25,false),
        new Run("motion03-native",0,false),new Run("motion04-sr33",33,false),
        new Run("motion05-native",0,false),new Run("motion06-sr50",50,false),
        new Run("motion07-native-fifo",0,true),new Run("motion08-sr25-fifo",25,true));
    private final List<Run> runs=nativeOnly ? requestedRuns.stream().filter(r->r.strength()==0 && !r.vsync()).toList() : requestedRuns;
    private static MotionBenchmark active;
    private Path root,game;
    private CaptureRoute route;
    private CaptureRoute.Waypoint anchor;
    private Set<Path> previous;
    private int state,index=-1,quality=-1,qualityFrame,retries;
    private long deadline,epoch,nextImage,extracts;
    private double minYaw,maxYaw,maxStep,lastYaw;
    private String surface;
    private final java.lang.management.ThreadMXBean cpu = java.lang.management.ManagementFactory.getThreadMXBean();
    private long previousCpu=-1,cpuNanos,cpuFrames;
    @Override public void onInitializeClient() {
        if(!Boolean.getBoolean("metalmod.motionBenchmark")) return;
        active=this;
        var executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"MotionBenchmark");t.setDaemon(true);return t;});
        executor.scheduleAtFixedRate(()->Minecraft.getInstance().execute(()->{
            try { tick(Minecraft.getInstance()); } catch(Throwable error) {error.printStackTrace();state=99;Minecraft.getInstance().stop();}
        }),1,100,TimeUnit.MILLISECONDS);
    }
    public static void beforeExtract() {
        var a=active;var mc=Minecraft.getInstance();
        if(a==null || a.anchor==null || a.state==99 || mc.player==null) return;
        // Diagnostic addon only: CPU consumed between camera extractions, excluding blocked
        // presentation/GPU waits. Keep this separate from F8 wall-clock frame intervals.
        if(a.submissionCheck && a.cpu.isCurrentThreadCpuTimeSupported() && a.cpu.isThreadCpuTimeEnabled()) {
            long sample=a.cpu.getCurrentThreadCpuTime();
            if(a.state==3 && PerformanceCapture.isRecording() && sample>=0) {
                if(a.previousCpu>=0) {a.cpuNanos+=sample-a.previousCpu;a.cpuFrames++;}
                a.previousCpu=sample;
            } else a.previousCpu=-1;
        }
        double seconds=(System.nanoTime()-a.epoch)/1e9;
        double phase=a.staticCamera?0:2*Math.PI*seconds/30;
        double yaw=a.anchor.yaw()+12*Math.sin(phase);
        mc.player.setPos(a.anchor.x()+3*Math.sin(phase),a.anchor.y(),a.anchor.z()+2*(Math.cos(phase)-1));
        mc.player.setYRot((float)yaw);mc.player.setXRot(a.anchor.pitch());
        // Pose is sampled at render time; prevent the tick interpolator applying a second motion.
        mc.player.setOldPosAndRot();
        a.minYaw=Math.min(a.minYaw,yaw);a.maxYaw=Math.max(a.maxYaw,yaw);
        if(a.extracts++>0) a.maxStep=Math.max(a.maxStep,Math.abs(yaw-a.lastYaw));
        a.lastYaw=yaw;
    }
    public static void afterRender() {
        var a=active;
        if(a==null || a.state!=4 || System.nanoTime()<a.nextImage) return;
        a.nextImage=System.nanoTime()+100_000_000L;
        try { a.image(Minecraft.getInstance()); } catch(Exception error) {error.printStackTrace();a.state=99;}
    }
    private Set<Path> captures() throws Exception {
        try(var paths=Files.list(root)) {return new HashSet<>(paths.filter(Files::isDirectory).filter(p->Files.exists(p.resolve("summary.txt"))).toList());}
    }
    private void resetMotion(long now) {epoch=now;extracts=0;minYaw=Double.POSITIVE_INFINITY;maxYaw=Double.NEGATIVE_INFINITY;maxStep=0;}
    private void tick(Minecraft mc) throws Exception {
        if(state==99 || mc.level==null || mc.player==null || !mc.isGameLoadFinished()) return;
        long now=System.nanoTime();
        if(state==0) {
            game=mc.gameDirectory.toPath();
            if(!Files.exists(game.resolve(".metalmod-benchmark-copy"))) throw new IllegalStateException("disposable clone marker missing");
            // Baseline performance is real native frames; feature comparisons must be explicit.
            FrameGenerationSettings.chooseEnabled(false);TemporalJitterProof.chooseEnabled(false);
            UpscalingSettings.chooseEnabled(false);
            root=game.resolve("debug/metalmod");Files.createDirectories(root);
            route=CaptureRouteStore.load(mc);if(route==null || route.isEmpty()) throw new IllegalStateException("anchor route missing");
            anchor=route.waypoint(0);
            // F8 must not drive its teleport route while this per-frame motion owns the test camera.
            Files.move(root.resolve("routes/minecraft.overworld.json"),root.resolve("motion-anchor-route.json"),StandardCopyOption.REPLACE_EXISTING);
            mc.options.pauseOnLostFocus=false;mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
            mc.options.framerateLimit().set(260);mc.gui.setScreen(null);
            CaptureRoutePlayer.prepare(mc);new CaptureRoutePlayer(route).arm(mc);
            resetMotion(now);deadline=now+60_000_000_000L;state=1;
            DisplayProbe.activate();GLFW.glfwFocusWindow(mc.getWindow().handle());
            System.out.println("[MotionBenchmark] initial smooth warmup "+anchor);return;
        }
        if(state==1 && now>=deadline) {next(mc,now);return;}
        if(state==2 && now>=deadline) {
            var f=mc.getClass().getDeclaredField("windowSurface");f.setAccessible(true);
            var config=((GpuSurface)f.get(mc)).currentConfiguration().orElseThrow();
            var expected=runs.get(index).vsync()?GpuSurface.PresentMode.FIFO:GpuSurface.PresentMode.IMMEDIATE;
            if(config.presentMode()!=expected) throw new IllegalStateException("surface mode mismatch "+config);
            surface=config.toString();DisplayProbe.activate();GLFW.glfwFocusWindow(mc.getWindow().handle());
            previous=captures();DisplayProbe.reset();PerformanceCapture.toggle(mc);resetMotion(now+5_000_000_000L);
            previousCpu=-1;cpuNanos=0;cpuFrames=0;
            state=3;deadline=now+90_000_000_000L;
            System.out.println("[MotionBenchmark] capture "+runs.get(index)+" effective "+surface);return;
        }
        if(state==3) {
            if(offscreenComparison && now>=epoch && !OffscreenCompletion.started()) OffscreenCompletion.begin();
            if(PerformanceCapture.isRecording()) {
                if((submissionCheck || Boolean.getBoolean("metalmod.displayCheckOnly")) && now>epoch+30_000_000_000L) {if(offscreenComparison) OffscreenCompletion.finish();PerformanceCapture.toggle(mc);return;}
                if(now>deadline)throw new IllegalStateException("capture timeout");return;}
            var current=captures();current.removeAll(previous);if(current.isEmpty())return;
            if(current.size()!=1)throw new IllegalStateException("ambiguous export");
            Path folder=current.iterator().next();long interrupted;
            try(var lines=Files.lines(folder.resolve("frames.csv"))) {
                interrupted=lines.skip(1).filter(l->{var c=l.split(",",7);
                    return !c[3].equals("0") || !c[4].equals("0") || !c[5].equals("1");}).count();
            }
            if(interrupted>0) {Files.writeString(folder.resolve("REJECTED.txt"),"paused/menu/unfocused intervals "+interrupted);if(++retries>=3)throw new IllegalStateException("three interrupted captures");index--;next(mc,now);return;}
            retries=0;DisplayProbe.save(folder);var run=runs.get(index);
            if(offscreenComparison) {
                OffscreenCompletion.save(folder);
                Files.writeString(folder.resolve("INSTRUMENTED.txt"),"Bounded completion-fence presentation experiment; compare only with its paired controls.\n");
            }
            if(Boolean.getBoolean("metalmod.benchmarkDiagnostic") || Boolean.getBoolean("metalmod.gpuStageTiming"))
                Files.writeString(folder.resolve("INSTRUMENTED.txt"),
                    "Diagnostic run or GPU stage counters enabled; exclude from throughput and CPU stall comparisons.\n");
            if(submissionCheck) Files.writeString(folder.resolve("render-thread-cpu.json"),
                "{\"samples\":"+cpuFrames+",\"total_ns\":"+cpuNanos+",\"mean_ms\":"+
                (cpuFrames>0?Double.toString(cpuNanos/1e6/cpuFrames):"null")+"}\n");
            Files.writeString(folder.resolve("motion.txt"),"Surface: "+surface+"\nCamera extractions: "+extracts+"\nYaw range: "+minYaw+".."+maxYaw+"\nMax yaw step: "+maxStep+"\n"+(staticCamera?"Fixed saved waypoint\n":"Smooth sinusoid: 30s period, +/-12deg yaw, 3x2 block translation\n")+"No screenshots during capture\n");
            Files.writeString(root.resolve("benchmark-index.tsv"),run.name()+"\t"+folder.getFileName()+"\t"+run+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
            // Renderer comparisons retain a terrain image only after the timed capture ends.
            if(!rendererComparison.isEmpty()) {quality=run.strength();if(!offscreenComparison)qualityFrame=0;image(mc);}
            System.out.println("[MotionBenchmark] complete "+run.name());next(mc,now);return;
        }
        if(state==4 && now>=deadline) {
            Files.writeString(root.resolve("quality"+quality+".txt"),"Frames "+qualityFrame+"\nExposes a smooth 10s excerpt; cropped ROI, nominal 10Hz readback; not performance data.\n");
            nextQuality(mc,now);
        }
    }
    private void next(Minecraft mc,long now) throws Exception {
        if(++index>=runs.size()) {
            // The short display checks still finish with all four quality excerpts.
            if(submissionCheck) quality=3;
            nextQuality(mc,now);return;
        }
        var run=runs.get(index);
        if(offscreenComparison) OffscreenCompletion.mode(run.name().startsWith("offscreen"));
        if(submissionCheck && rendererComparison.isEmpty() && !offscreenComparison) System.setProperty(System.getProperty("metalmod.comparisonProperty","metalmod.commandBatching"),Boolean.toString(run.name().endsWith("-on")));
        UpscalingSettings.chooseEnabled(run.strength()>0);UpscalingSettings.chooseStrength(run.strength());
        System.setProperty("metalmod.fxAntialias","true");mc.options.enableVsync().set(run.vsync());
        new CaptureRoutePlayer(route).arm(mc);resetMotion(now);deadline=now+20_000_000_000L;state=2;
        DisplayProbe.activate();GLFW.glfwFocusWindow(mc.getWindow().handle());System.out.println("[MotionBenchmark] warmup "+run);
    }
    private void nextQuality(Minecraft mc,long now) throws Exception {
        if(nativeOnly || ++quality>=4) {
            Files.move(root.resolve("motion-anchor-route.json"),root.resolve("routes/minecraft.overworld.json"),StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(root.resolve("motion-complete.txt"),runs.size()+" performance captures exported\n");
            OffscreenCompletion.drain();state=99;System.out.println("[MotionBenchmark] COMPLETE");mc.stop();return;
        }
        int strength=new int[]{0,25,33,50}[quality];UpscalingSettings.chooseEnabled(strength>0);UpscalingSettings.chooseStrength(strength);
        mc.options.enableVsync().set(false);qualityFrame=0;resetMotion(now+3_000_000_000L);
        nextImage=now+3_000_000_000L;deadline=now+13_000_000_000L;state=4;
        DisplayProbe.activate();GLFW.glfwFocusWindow(mc.getWindow().handle());System.out.println("[MotionBenchmark] quality "+strength);
    }
    private void image(Minecraft mc) throws Exception {
        var f=mc.gameRenderer.getClass().getDeclaredField("mainRenderTarget");f.setAccessible(true);
        Path path=game.resolve("motion-quality/"+quality+"/"+String.format("%04d.png",qualityFrame++));Files.createDirectories(path.getParent());
        Screenshot.takeScreenshot((RenderTarget)f.get(mc.gameRenderer),image->{
            int w=Math.min(1920,image.getWidth()),h=Math.min(900,image.getHeight());
            try(image;var roi=new NativeImage(w,h,false)) {
                int top=image.getHeight()/2;
                // Source screenshot is bottom-up on Metal. Select the centre/lower terrain ROI.
                image.copyRect(roi,(image.getWidth()-w)/2,image.getHeight()-top-h,0,0,w,h,false,true);
                roi.writeToFile(path);
            } catch(Exception error) {throw new RuntimeException(error);}
        });
    }
}
