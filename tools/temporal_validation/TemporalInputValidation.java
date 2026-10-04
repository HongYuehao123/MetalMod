package net.metalmod.validation;

import net.fabricmc.api.ClientModInitializer;
import net.metalmod.Diagnostics;
import net.metalmod.client.gui.MetalModMetalFxScreen;
import net.metalmod.backend.*;
import net.metalmod.upscaling.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import org.lwjgl.glfw.GLFW;
import java.lang.foreign.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;

/** Disposable copied-world validation only; not packaged in the production mod. */
public final class TemporalInputValidation implements ClientModInitializer {
    private record Stage(String name,boolean on,int strength,boolean jitter,String screen,int guiScale,boolean resize) {}
    private final List<Stage> stages=List.of(
            new Stage("01-native-off",false,25,false,"world",2,false),
            new Stage("02-native-100",true,0,true,"world",2,false),
            new Stage("03-spatial-25",true,25,false,"world",2,false),
            new Stage("04-jitter-25",true,25,true,"world",2,false),
            new Stage("05-jitter-33",true,33,true,"world",2,false),
            new Stage("06-jitter-50",true,50,true,"world",2,false),
            new Stage("07-jitter-resize",true,25,true,"world",2,true),
            new Stage("08-jitter-inventory",true,25,true,"inventory",2,false),
            new Stage("09-jitter-pause",true,50,true,"pause",4,false),
            new Stage("09b-sr-settings",true,25,true,"settings",2,false),
            new Stage("09c-test-off",true,25,false,"settings",2,false),
            new Stage("09d-test-native",true,0,true,"settings",2,false),
            new Stage("10a-static-sequence",true,50,true,"world",2,false),
            new Stage("10b-camera-motion",true,50,true,"world",2,false),
            new Stage("10c-fast-turn",true,50,true,"world",2,false),
            new Stage("10d-teleport",true,50,true,"world",2,false),
            new Stage("10e-fov",true,50,true,"world",2,false),
            new Stage("10f-render-gap",true,50,true,"world",2,false),
            new Stage("10g-resource-reload",true,50,true,"world",2,false),
            new Stage("10h-entities-rain",true,50,true,"world",2,false),
            new Stage("10ha-lights-off",true,50,true,"world",2,false),
            new Stage("10hc-lights-on",true,50,true,"world",2,false),
            new Stage("10hb-moving-block",true,50,true,"world",2,false),
            new Stage("10i-generation-stress",true,50,true,"world",2,false),
            new Stage("10j-minimize",true,50,true,"world",2,false),
            new Stage("10k-nether",true,50,true,"world",2,false),
            new Stage("10l-overworld",true,50,true,"world",2,false),
            new Stage("10m-reconnect",true,50,true,"world",2,false),
            new Stage("10n-native-quality",false,50,false,"world",2,false),
            new Stage("10o-spatial-quality",true,50,false,"world",2,false),
            new Stage("10p-temporal-quality",true,50,true,"world",2,false),
            new Stage("10q-benchmark-native-1",false,50,false,"world",2,false),
            new Stage("10r-benchmark-spatial-1",true,50,false,"world",2,false),
            new Stage("10s-benchmark-temporal-1",true,50,true,"world",2,false),
            new Stage("10t-benchmark-temporal-2",true,50,true,"world",2,false),
            new Stage("10u-benchmark-spatial-2",true,50,false,"world",2,false),
            new Stage("10v-benchmark-native-2",false,50,false,"world",2,false),
            new Stage("10-proof-disabled",true,25,false,"world",2,false),
            new Stage("11-return-native",false,25,true,"world",2,false),
            new Stage("12-menu-only",true,25,true,"menu",2,false));
    private static TemporalInputValidation active;
    private Path root;
    private Field coordinatorField,targetField;
    private int index=-1,stageFrames,failures,sequenceFrames;
    private long resetsStart,generationsStart;
    private net.minecraft.world.phys.Vec3 returnPosition;
    private boolean restored;
    private net.minecraft.core.BlockPos piston;
    private net.minecraft.world.phys.Vec3 originalPosition;
    private float originalYaw,originalPitch;
    private final List<Long> intervals=new ArrayList<>(),gpuTimes=new ArrayList<>();
    private long lastBenchmarkTime,benchmarkStart;
    private int unfocused;
    private boolean qualityFrozen;
    private int maxLightRegions;
    private java.lang.invoke.MethodHandle activate;
    private long start,stageStart,depthStart,frames;
    private boolean done;
    private List<String> checks=new ArrayList<>();

    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("metalmod.temporalInputValidation")) return;
        var game=Minecraft.getInstance().gameDirectory.toPath();
        if (!Files.exists(game.resolve(".metalmod-benchmark-copy")))
            throw new IllegalStateException("Disposable copied-world marker missing");
        root=game.resolve("validation");
        try { Files.createDirectories(root); } catch(Exception error) { throw new RuntimeException(error); }
        active=this; start=System.nanoTime();
    }
    public static void afterRender() {
        var a=active;
        if (a==null || a.done) return;
        try { a.frame(Minecraft.getInstance()); }
        catch(Throwable error) {
            error.printStackTrace();
            try { a.check("runtime exception",false,error.toString());a.finish(Minecraft.getInstance()); }
            catch(Exception secondary) { secondary.printStackTrace();a.done=true; }
        }
    }
    private MetalFxCoordinator coordinator(Minecraft mc) throws Exception {
        if (coordinatorField==null) {
            coordinatorField=mc.gameRenderer.getClass().getDeclaredField("metalmod$fx");coordinatorField.setAccessible(true);
            targetField=mc.gameRenderer.getClass().getDeclaredField("mainRenderTarget");targetField.setAccessible(true);
        }
        return (MetalFxCoordinator)coordinatorField.get(mc.gameRenderer);
    }
    private void frame(Minecraft mc) throws Exception {
        long now=System.nanoTime();
        if (now-start>450_000_000_000L) { check("bounded runtime",false,"timeout");finish(mc);return; }
        if (index<0) {
            if (mc.level==null || mc.player==null || !mc.isGameLoadFinished()) return;
            mc.options.pauseOnLostFocus=false;mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
            mc.options.enableVsync().set(false);mc.options.framerateLimit().set(60);
            originalPosition=mc.player.position();originalYaw=mc.player.getYRot();originalPitch=mc.player.getXRot();
            var server=mc.getSingleplayerServer();
            server.executeIfPossible(()->{
                var player=server.getPlayerList().getPlayers().getFirst();
                var source=server.createCommandSourceStack().withEntity(player).withPosition(player.position())
                        .withLevel((net.minecraft.server.level.ServerLevel)player.level()).withSuppressedOutput();
                for (var cmd:List.of("gamemode creative","time set noon","weather clear",
                        "item replace entity @s weapon.mainhand with minecraft:torch"))
                    server.getCommands().performPrefixedCommand(source,cmd);
            });
            next(mc,now);return;
        }
        if (!stages.get(index).screen().equals("menu") && (mc.level==null || !mc.isGameLoadFinished())) return;
        if(stages.get(index).name().equals("10j-minimize") && !restored && now-stageStart>1_000_000_000L){
            GLFW.glfwRestoreWindow(mc.getWindow().handle());GLFW.glfwFocusWindow(mc.getWindow().handle());restored=true;
        }
        stageFrames++;frames++;
        maxLightRegions=Math.max(maxLightRegions,TemporalSceneMotion.stats().lightRegions());
        var name=stages.get(index).name();
        if(name.equals("10b-camera-motion")) {
            mc.player.setYRot(mc.player.getYRot()+0.12f);
            mc.player.setYHeadRot(mc.player.getYRot());
        }
        if(name.contains("benchmark")) {
            if(now-stageStart<5_000_000_000L){lastBenchmarkTime=0;return;}
            if(lastBenchmarkTime==0){lastBenchmarkTime=benchmarkStart=now;return;}
            intervals.add(now-lastBenchmarkTime);lastBenchmarkTime=now;
            long gpu=MetalFxCoordinator.stats().gpuDurationNs();if(gpu>0)gpuTimes.add(gpu);
            if(!mc.isWindowActive())unfocused++;
            if(now-benchmarkStart<20_000_000_000L)return;
            var sr=MetalFxCoordinator.stats();
            var surface=mc.getClass().getDeclaredField("windowSurface");surface.setAccessible(true);
            var configuration=((com.mojang.blaze3d.systems.GpuSurface)surface.get(mc)).currentConfiguration().orElseThrow();
            check(name+" benchmark reconstruction",sr.effective().equals(stages.get(index).on()
                    ?stages.get(index).jitter()?"MetalFX temporal":"MetalFX spatial":"Native"),sr.summary());
            check(name+" benchmark healthy",sr.failures()==0&&sr.recoveries()==0,sr.toString());
            var ordered=intervals.stream().mapToLong(Long::longValue).sorted().toArray();
            double mean=intervals.stream().mapToLong(Long::longValue).average().orElseThrow()/1e6;
            double p95=ordered[(int)((ordered.length-1)*.95)]/1e6;
            double gpuMs=gpuTimes.stream().mapToLong(Long::longValue).average().orElse(-1)/1e6;
            Files.writeString(root.resolve(name+".txt"),"samples="+intervals.size()+"\nmean_ms="+mean
                    +"\nfps="+(1000/mean)+"\np95_ms="+p95+"\ngpu_batch_mean_ms="+gpuMs
                    +"\nunfocused="+unfocused+"\nconfigured_fps="+mc.options.framerateLimit().get()
                    +"\nvsync="+mc.options.enableVsync().get()+"\nsurface="+configuration+"\n"+sr+"\n");
            var csv=new StringBuilder("frame_interval_ns\n");for(var value:intervals)csv.append(value).append('\n');
            Files.writeString(root.resolve(name+".csv"),csv);
            next(mc,now);return;
        }
        if(name.equals("10hb-moving-block") && stageFrames%20==0)
            command(mc,"setblock "+piston.getX()+" "+piston.getY()+" "+(piston.getZ()+1)
                    +(stageFrames%40==0?" minecraft:redstone_block":" minecraft:air"));
        if(name.equals("10n-native-quality") && !qualityFrozen && now-stageStart>6_000_000_000L){
            command(mc,"tick freeze");qualityFrozen=true;
        }
        if (stageFrames<120 || now-stageStart<(name.equals("10n-native-quality")?8_000_000_000L:5_000_000_000L)) return;
        var stage=stages.get(index); var sr=MetalFxCoordinator.stats();var proof=TemporalJitterProof.stats();
        if(stage.name().equals("10a-static-sequence") || stage.name().equals("10b-camera-motion")
                ||stage.name().equals("10h-entities-rain")||stage.name().matches("10[nop]-.*")) {
            if(sequenceFrames<16){captureSequence(mc,stage.name()+"-"+String.format("%02d",sequenceFrames++));return;}
        }
        if(proof.applied() && proof.frames()<(stage.name().equals("10i-generation-stress")?20:120)) return;
        if(stage.name().equals("10h-entities-rain")||stage.name().equals("10hc-lights-on")) {
            check(stage.name()+" live dynamic lights",net.metalmod.lighting.LightingSettings.active()
                    && !net.metalmod.lighting.LightCollector.current().lights().isEmpty(),
                    "lights="+net.metalmod.lighting.LightCollector.current().lights().size());
            check(stage.name()+" changed-light rejection regions",maxLightRegions>0,"max="+maxLightRegions);
        }
        if(stage.name().equals("10ha-lights-off"))
            check("dynamic lighting off while temporal stays active",!net.metalmod.lighting.LightingSettings.active(),"");
        if(stage.name().equals("10hb-moving-block"))
            check("live moving-block pose producer",Diagnostics.hasHook("Temporal.movingBlock"),TemporalSceneMotion.stats().toString());
        if(stage.name().matches("10[c-gj-m]-.*"))
            check(stage.name()+" history reset",TemporalSceneMotion.stats().resets()>resetsStart,TemporalSceneMotion.stats().toString());
        if(stage.name().equals("10i-generation-stress")){
            check("temporal repeated generation retirement",sr.generation()-generationsStart>=5,sr.toString());
            System.clearProperty("metalmod.fxRecreateEvery");
        }

        var fx=coordinator(mc);boolean reduced=stage.on() && stage.strength()>0 && !stage.screen().equals("menu");
        check(stage.name()+" effective resolution",reduced?sr.sceneWidth()<sr.outputWidth() && sr.sceneHeight()<sr.outputHeight():
                sr.sceneWidth()==sr.outputWidth() && sr.sceneHeight()==sr.outputHeight(),sr.summary());
        var dimensions=UpscalingSettings.dimensions(sr.outputWidth(),sr.outputHeight(),
                new UpscalingSettings.Snapshot(reduced,stage.strength(),0));
        check(stage.name()+" exact dimensions",sr.sceneWidth()==dimensions.width()
                && sr.sceneHeight()==dimensions.height(),dimensions.toString());
        if (stage.screen().equals("settings")) {
            check(stage.name()+" settings screen",mc.gui.screen() instanceof MetalModMetalFxScreen,"");
            var saved=new net.metalmod.config.MetalConfig();saved.load();
            check(stage.name()+" saved temporal switch",saved.enableTemporalUpscaling==stage.jitter()
                    && TemporalJitterProof.requested()==stage.jitter(),"requested="+TemporalJitterProof.requested());
        }
        check(stage.name()+" reconstruction mode",reduced?sr.effective().equals(stage.jitter()?"MetalFX temporal":"MetalFX spatial")
                :sr.effective().equals("Native"),sr.effective());
        check(stage.name()+" spatial health",sr.failures()==0 && sr.recoveries()==0,sr.toString());
        boolean expected=reduced && stage.jitter();
        check(stage.name()+" jitter state",proof.applied()==expected,proof.toString());
        if (expected) {
            check(stage.name()+" world/hand uploads",proof.worldUploads()>(stage.name().equals("10i-generation-stress")?10:60) && proof.handUploads()==0,proof.toString());
            check(stage.name()+" world depth snapshots",fx.temporalDepthCopies()-depthStart>60
                    && fx.temporalWorldDepth().address()!=0,"copies="+(fx.temporalDepthCopies()-depthStart));
            sampleDepth(mc,fx,stage.name());
            check(stage.name()+" proof closed before UI",!fx.jitterProof().active(),"");
        } else check(stage.name()+" no proof depth work",fx.temporalDepthCopies()==depthStart,"copies="+fx.temporalDepthCopies());
        check(stage.name()+" backend health",MetalDevice.resourceFailureCount()==0 && MetalDevice.pipelineFailureCount()==0
                && MetalDevice.unboundBindingCount()==0 && MetalDevice.missingVertexAttributeCount()==0,
                "resource/pipeline/unbound/attributes="+MetalDevice.resourceFailureCount()+"/"+MetalDevice.pipelineFailureCount()
                +"/"+MetalDevice.unboundBindingCount()+"/"+MetalDevice.missingVertexAttributeCount());
        capture(mc,stage.name());
        Files.writeString(root.resolve(stage.name()+".txt"),"frames="+stageFrames+"\n"+sr+"\n"+proof+"\n");
        next(mc,now);
    }
    private void sampleDepth(Minecraft mc,MetalFxCoordinator fx,String label) throws Exception {
        var sr=MetalFxCoordinator.stats();
        try(var arena=Arena.ofConfined()) {
            int w=sr.sceneWidth(),h=sr.sceneHeight();var memory=arena.allocate((long)w*h*4,4);
            MetalNative.queueSynchronize(MetalDevice.active().queueHandle());
            int rc=MetalNative.textureReadRegion(fx.temporalWorldDepth(),0,0,0,0,w,h,memory,memory.byteSize(),w*4L);
            int finite=0,nonzero=0;float max=0;
            for(long i=0;i<(long)w*h;i++) {float value=memory.getAtIndex(ValueLayout.JAVA_FLOAT,i);
                if(Float.isFinite(value)&&value>=0&&value<=1)finite++;
                if(value>0)nonzero++;max=Math.max(max,value);
            }
            check(label+" rendered world depth data",rc==0 && finite==(long)w*h && nonzero>w,
                    "nonzero="+nonzero+"/"+(w*h)+" max="+max);
        }
    }
    private void next(Minecraft mc,long now) throws Exception {
        if (++index>=stages.size()) { finish(mc);return; }
        var stage=stages.get(index);
        UpscalingSettings.chooseEnabled(stage.on());UpscalingSettings.chooseStrength(stage.strength());
        // Opposite launch flag proves the live choice can still be changed from the screen.
        System.setProperty("metalmod.temporalJitterProof",Boolean.toString(!stage.jitter()));
        TemporalJitterProof.chooseEnabled(stage.screen().equals("settings") ? !stage.jitter() : stage.jitter());
        mc.options.guiScale().set(stage.guiScale());mc.resizeGui();
        if (stage.resize()) GLFW.glfwSetWindowSize(mc.getWindow().handle(),1921,1081);
        switch(stage.screen()) {
            case "inventory" -> mc.gui.setScreen(new InventoryScreen(mc.player));
            case "settings" -> mc.gui.setScreen(new MetalModMetalFxScreen(null));
            case "pause" -> mc.gui.setScreen(new PauseScreen(true));
            case "menu" -> mc.disconnect(new TitleScreen(),false);
            default -> mc.gui.setScreen(null);
        }
        if (stage.screen().equals("settings")) {
            var button=mc.gui.screen().children().stream()
                    .filter(child->child instanceof net.minecraft.client.gui.components.Button b
                            && b.getMessage().getString().startsWith("Temporal Upscaling:"))
                    .map(child->(net.minecraft.client.gui.components.Button)child).findFirst().orElseThrow();
            button.onPress(null);
            check(stage.name()+" temporal button callback",TemporalJitterProof.requested()==stage.jitter(),"");
        }
        stageFrames=sequenceFrames=maxLightRegions=0;restored=false;qualityFrozen=false;intervals.clear();gpuTimes.clear();lastBenchmarkTime=0;unfocused=0;
        mc.options.framerateLimit().set(stage.name().contains("benchmark")?260:60);
        if(stage.name().contains("benchmark")){activateApplication();GLFW.glfwFocusWindow(mc.getWindow().handle());}stageStart=now;depthStart=coordinator(mc).temporalDepthCopies();
        resetsStart=TemporalSceneMotion.stats().resets();generationsStart=MetalFxCoordinator.stats().generation();
        switch(stage.name()) {
            case "10c-fast-turn" -> {mc.player.setYRot(mc.player.getYRot()+120);mc.player.setYHeadRot(mc.player.getYRot());}
            case "10d-teleport" -> command(mc,"tp @s ~16 ~ ~");
            case "10e-fov" -> mc.options.fov().set(110);
            case "10f-render-gap" -> Thread.sleep(350);
            case "10g-resource-reload" -> mc.reloadResourcePacks();
            case "10h-entities-rain" -> {
                command(mc,"summon minecraft:pig ~ ~ ~3");command(mc,"summon minecraft:chicken ~2 ~ ~3");
                command(mc,"summon minecraft:item ~ ~1 ~2 {Item:{id:\"minecraft:torch\",count:1}}");
                command(mc,"tp @s ~ ~ ~ 0 15");
                command(mc,"weather rain");command(mc,"time set midnight");
            }
            case "10ha-lights-off" -> net.metalmod.lighting.LightingSettings.chooseDynamicLights(false);
            case "10hc-lights-on" -> net.metalmod.lighting.LightingSettings.chooseDynamicLights(true);
            case "10hb-moving-block" -> {
                piston=mc.player.blockPosition().offset(0,0,4);
                int px=piston.getX(),py=piston.getY(),pz=piston.getZ();
                command(mc,"fill "+(px-3)+" "+(py-1)+" "+(pz-5)+" "+(px+3)+" "+(py-1)+" "+(pz+3)+" minecraft:stone");
                command(mc,"fill "+(px-2)+" "+py+" "+(pz-4)+" "+(px+2)+" "+(py+3)+" "+(pz+2)+" minecraft:air");
                command(mc,"setblock "+px+" "+py+" "+pz+" minecraft:sticky_piston[facing=north]");
                command(mc,"setblock "+px+" "+py+" "+(pz-1)+" minecraft:stone");
                command(mc,"tp @s "+(px+0.5)+" "+(py+2.5)+" "+(pz-4.5)+" 0 25");
            }
            case "10n-native-quality" -> {
                command(mc,String.format(java.util.Locale.ROOT,"tp @s %.5f %.5f %.5f %.5f %.5f",
                        originalPosition.x,originalPosition.y,originalPosition.z,originalYaw,originalPitch));
                command(mc,"time set noon");command(mc,"weather clear");
            }
            case "10i-generation-stress" -> System.setProperty("metalmod.fxRecreateEvery","45");
            case "10j-minimize" -> GLFW.glfwIconifyWindow(mc.getWindow().handle());
            case "10k-nether" -> {returnPosition=mc.player.position();command(mc,"execute in minecraft:the_nether run tp @s 0 80 0");}
            case "10l-overworld" -> command(mc,String.format(java.util.Locale.ROOT,
                    "execute in minecraft:overworld run tp @s %.3f %.3f %.3f",returnPosition.x,returnPosition.y,returnPosition.z));
            case "10m-reconnect" -> {mc.disconnect(new TitleScreen(),false);
                mc.createWorldOpenFlows().openWorld(System.getProperty("metalmod.validationWorld"),()->{});}

        }
        System.out.println("[TemporalInputValidation] STAGE "+stage);
    }
    private void activateApplication() throws Exception {
        if(activate==null){
            var lookup=SymbolLookup.libraryLookup(Path.of(System.getProperty("metalmod.validationFocus")),Arena.global());
            activate=Linker.nativeLinker().downcallHandle(lookup.find("metalmod_validation_activate").orElseThrow(),FunctionDescriptor.ofVoid());
        }
        try{activate.invokeExact();}catch(Throwable error){throw new RuntimeException(error);}
    }
    private void command(Minecraft mc,String text) {
        var server=mc.getSingleplayerServer();server.executeIfPossible(()->{
            var player=server.getPlayerList().getPlayers().getFirst();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withEntity(player).withPosition(player.position())
                        .withLevel((net.minecraft.server.level.ServerLevel)player.level()).withSuppressedOutput(),text);
        });
    }
    private void captureSequence(Minecraft mc,String name) throws Exception {
        var target=(RenderTarget)targetField.get(mc.gameRenderer);
        int width=Math.min(512,target.width),height=Math.min(288,target.height);
        int x=(int)(target.width*0.18),y=(int)(target.height*0.65);
        x=Math.min(x,target.width-width);y=Math.min(y,target.height-height);
        try(var arena=Arena.ofConfined();var image=new NativeImage(width,height,false)) {
            var memory=arena.allocate((long)width*height*4,4);
            MetalNative.queueSynchronize(MetalDevice.active().queueHandle());
            var texture=(MetalTexture)target.getColorTexture();
            int rc=MetalNative.textureReadRegion(texture.handle(),0,0,x,y,width,height,memory,memory.byteSize(),width*4L);
            if(rc!=0)throw new IllegalStateException("sequence readback "+rc);
            boolean bgra=MetalFormat.mtlPixelFormat(texture.getFormat())==80;
            for(int py=0;py<height;py++)for(int px=0;px<width;px++){
                long offset=((long)py*width+px)*4;
                int r=memory.get(ValueLayout.JAVA_BYTE,offset+(bgra?2:0))&255;
                int g=memory.get(ValueLayout.JAVA_BYTE,offset+1)&255;
                int blue=memory.get(ValueLayout.JAVA_BYTE,offset+(bgra?0:2))&255;
                int alpha=memory.get(ValueLayout.JAVA_BYTE,offset+3)&255;
                image.setPixel(px,py,(alpha<<24)|(r<<16)|(g<<8)|blue);
            }
            image.writeToFile(root.resolve(name+".png"));
            Files.writeString(root.resolve(name+".txt"),"time_ns="+System.nanoTime()+"\n"+TemporalJitterProof.stats()
                    +"\n"+TemporalSceneMotion.stats()+"\nroi="+x+","+y+","+width+","+height+"\n");
        }
    }
    private void capture(Minecraft mc,String name) throws Exception {
        var target=(RenderTarget)targetField.get(mc.gameRenderer);
        // Native output marker proves composition is still in native pixels after reconstruction.
        MetalNative.clearTexturesRegion(MetalDevice.active().queueHandle(),((MetalTexture)target.getColorTexture()).handle(),true,
                1,0,1,1,MemorySegment.NULL,false,0,8,8,1,1);
        Screenshot.takeScreenshot(target,image->{
            try(image;var upright=new NativeImage(image.getWidth(),image.getHeight(),false)) {
                image.copyRect(upright,0,0,0,0,image.getWidth(),image.getHeight(),false,true);
                // Screenshot's vanilla bottom-up conversion is undone only in this test add-on.
                int marker=upright.getPixel(8,8);
                check(name+" native one-pixel output marker",(marker&0xffffff)==0xff00ff,"ARGB="+Integer.toHexString(marker));
                upright.writeToFile(root.resolve(name+".png"));
            } catch(Exception error) { throw new RuntimeException(error); }
        });
    }
    private void check(String name,boolean passed,String detail) throws Exception {
        String line=(passed?"PASS ":"FAIL ")+name+" "+detail;
        System.out.println("[TemporalInputValidation] "+line);checks.add(line);
        if (!passed) failures++;
        Files.writeString(root.resolve("checks.txt"),line+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    private void finish(Minecraft mc) throws Exception {
        for (var hook:List.of("TemporalProof.worldProjection","Temporal.worldReconstruction","Temporal.entityIdentity","Temporal.movingBlock"))
            check("live hook "+hook,Diagnostics.hasHook(hook),"");
        done=true;
        Files.writeString(root.resolve("result.txt"),(failures==0?"PASS":"FAIL")+"\nchecks="+checks.size()
                +" failures="+failures+" frames="+frames+"\n");
        System.out.println("[TemporalInputValidation] COMPLETE failures="+failures+" frames="+frames);
        mc.stop();
    }
}
