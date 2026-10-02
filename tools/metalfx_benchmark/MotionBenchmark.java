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
    private final List<Run> runs=Boolean.getBoolean("metalmod.displayCheckOnly")
        ? List.of(new Run("display01-immediate",0,false),new Run("display02-fifo",0,true),new Run("display03-sr25-fifo",25,true)) : List.of(new Run("motion01-native",0,false),new Run("motion02-sr25",25,false),
        new Run("motion03-native",0,false),new Run("motion04-sr33",33,false),
        new Run("motion05-native",0,false),new Run("motion06-sr50",50,false),
        new Run("motion07-native-fifo",0,true),new Run("motion08-sr25-fifo",25,true));
    private static MotionBenchmark active;
    private Path root,game;
    private CaptureRoute route;
    private CaptureRoute.Waypoint anchor;
    private Set<Path> previous;
    private int state,index=-1,quality=-1,qualityFrame,retries;
    private long deadline,epoch,nextImage,extracts;
    private double minYaw,maxYaw,maxStep,lastYaw;
    private String surface;
    @Override public void onInitializeClient() {
        if(!Boolean.getBoolean("metalmod.motionBenchmark")) return;
        active=this;
        var executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"MotionBenchmark");t.setDaemon(true);return t;});
        executor.scheduleAtFixedRate(()->Minecraft.getInstance().execute(()->{
            try { tick(Minecraft.getInstance()); } catch(Throwable error) {error.printStackTrace();state=99;}
        }),1,100,TimeUnit.MILLISECONDS);
    }
    public static void beforeExtract() {
        var a=active;var mc=Minecraft.getInstance();
        if(a==null || a.anchor==null || a.state==99 || mc.player==null) return;
        double seconds=(System.nanoTime()-a.epoch)/1e9;
        double phase=2*Math.PI*seconds/30;
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
            state=3;deadline=now+90_000_000_000L;
            System.out.println("[MotionBenchmark] capture "+runs.get(index)+" effective "+surface);return;
        }
        if(state==3) {
            if(PerformanceCapture.isRecording()) {
                if(Boolean.getBoolean("metalmod.displayCheckOnly") && now>epoch+30_000_000_000L) {PerformanceCapture.toggle(mc);return;}
                if(now>deadline)throw new IllegalStateException("capture timeout");return;}
            var current=captures();current.removeAll(previous);if(current.isEmpty())return;
            if(current.size()!=1)throw new IllegalStateException("ambiguous export");
            Path folder=current.iterator().next();long unfocused;
            try(var lines=Files.lines(folder.resolve("frames.csv"))) {unfocused=lines.skip(1).filter(l->!l.split(",",7)[5].equals("1")).count();}
            if(unfocused>0) {Files.writeString(folder.resolve("REJECTED.txt"),"unfocused "+unfocused);if(++retries>=3)throw new IllegalStateException("three focus failures");index--;next(mc,now);return;}
            retries=0;DisplayProbe.save(folder);var run=runs.get(index);
            Files.writeString(folder.resolve("motion.txt"),"Surface: "+surface+"\nCamera extractions: "+extracts+"\nYaw range: "+minYaw+".."+maxYaw+"\nMax yaw step: "+maxStep+"\nSmooth sinusoid: 30s period, +/-12deg yaw, 3x2 block translation\nNo screenshots during capture\n");
            Files.writeString(root.resolve("benchmark-index.tsv"),run.name()+"\t"+folder.getFileName()+"\t"+run+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
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
            nextQuality(mc,now);return;
        }
        var run=runs.get(index);UpscalingSettings.chooseEnabled(run.strength()>0);UpscalingSettings.chooseStrength(run.strength());
        System.setProperty("metalmod.fxAntialias","true");mc.options.enableVsync().set(run.vsync());
        new CaptureRoutePlayer(route).arm(mc);resetMotion(now);deadline=now+20_000_000_000L;state=2;
        DisplayProbe.activate();GLFW.glfwFocusWindow(mc.getWindow().handle());System.out.println("[MotionBenchmark] warmup "+run);
    }
    private void nextQuality(Minecraft mc,long now) throws Exception {
        if(++quality>=4) {
            Files.move(root.resolve("motion-anchor-route.json"),root.resolve("routes/minecraft.overworld.json"),StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(root.resolve("motion-complete.txt"),runs.size()+" performance captures exported\n");
            state=99;System.out.println("[MotionBenchmark] COMPLETE");mc.stop();return;
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
