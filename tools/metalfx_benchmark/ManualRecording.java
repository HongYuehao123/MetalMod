package net.metalmod.benchmark;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.metalmod.debug.PerformanceCapture;
import net.metalmod.upscaling.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Passive manual reproduction recorder. No camera, world, settings or render behaviour changes. */
public final class ManualRecording implements ClientModInitializer {
    private static ManualRecording active;
    private Path root,control;
    private Set<Path> previous;
    private boolean recording,prepared;
    private long prepareAt;
    private final StringBuilder samples=new StringBuilder();
    @Override public void onInitializeClient(){
        if(!Boolean.getBoolean("metalmod.manualRecording"))return;
        active=this;
        var executor=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"ManualRecording-control");t.setDaemon(true);return t;});
        executor.scheduleAtFixedRate(()->Minecraft.getInstance().execute(()->{
            try{tick(Minecraft.getInstance());}catch(Throwable e){e.printStackTrace();}
        }),1,100,TimeUnit.MILLISECONDS);
    }
    public static void afterRender(){
        var a=active;var mc=Minecraft.getInstance();
        if(a==null||!a.recording||mc.player==null)return;
        var s=TemporalSceneMotion.stats();var fx=MetalFxCoordinator.stats();var p=mc.player.position();
        a.samples.append(System.nanoTime()).append(',').append(p.x).append(',').append(p.y).append(',').append(p.z)
            .append(',').append(mc.player.getYRot()).append(',').append(mc.player.getXRot())
            .append(',').append(s.objects()).append(',').append(s.lightRegions()).append(',').append(s.resets())
            .append(',').append(fx.gpuDurationNs()).append(',').append(fx.effective()).append('\n');
    }
    private Set<Path> captures()throws Exception{
        try(var paths=Files.list(root)){return new HashSet<>(paths.filter(Files::isDirectory).filter(p->Files.exists(p.resolve("summary.txt"))).toList());}
    }
    private void tick(Minecraft mc)throws Exception{
        if(mc.player==null||mc.level==null||!mc.isGameLoadFinished())return;
        if(root==null){
            var game=mc.gameDirectory.toPath();
            if(!Files.exists(game.resolve(".metalmod-benchmark-copy")))throw new IllegalStateException("clone required");
            root=game.resolve("debug/metalmod");Files.createDirectories(root);control=game.resolve("capture.start");
            prepareAt=System.nanoTime()+20_000_000_000L;
            DisplayProbe.activate();return;
        }
        if(!prepared){
            if(System.nanoTime()<prepareAt)return;
            // Refresh the existing preference after startup; do not change the selected algorithm.
            UpscalingSettings.chooseEnabled(UpscalingSettings.current().enabled());prepared=true;
            Files.writeString(mc.gameDirectory.toPath().resolve("manual-ready.txt"),"Ready: user controls camera; existing settings refreshed\n");return;
        }
        if(Files.deleteIfExists(control)&&!recording){
            previous=captures();samples.setLength(0);
            samples.append("time_ns,x,y,z,yaw,pitch,regions,light_regions,resets,helper_gpu_ns,effective\n");
            DisplayProbe.reset();PerformanceCapture.toggle(mc);recording=true;
            System.out.println("[ManualRecording] START: user controls camera; ordinary F8 countdown and 60s capture");
        }
        if(recording&&!PerformanceCapture.isRecording()){
            var folders=captures();folders.removeAll(previous);if(folders.isEmpty())return;
            if(folders.size()!=1)throw new IllegalStateException("ambiguous export");var folder=folders.iterator().next();
            Files.writeString(folder.resolve("manual-motion.csv"),samples);DisplayProbe.save(folder);recording=false;
            Files.writeString(mc.gameDirectory.toPath().resolve("manual-complete.txt"),folder.toString()+"\n");
            System.out.println("[ManualRecording] COMPLETE "+folder);
        }
    }
}
