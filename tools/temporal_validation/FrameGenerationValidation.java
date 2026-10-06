package net.metalmod.validation;

import java.nio.file.*;
import java.util.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.InactivityFpsLimit;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.components.Button;
import net.metalmod.client.gui.MetalModMetalFxScreen;
import net.metalmod.backend.MetalDevice;
import net.metalmod.upscaling.*;
import org.lwjgl.glfw.GLFW;

/** Packaged copied-world interpolation delivery/control regression. Never installed normally. */
public final class FrameGenerationValidation {
    private static final String[] STAGES=Boolean.getBoolean("metalmod.frameGenerationDeliveryValidation")?new String[]{"limited-native-camera","limited-spatial-camera"}:Boolean.getBoolean("metalmod.frameGenerationRestartValidation")?new String[]{"startup-on","fast-native","menu-on"}:new String[]{"startup-on","native-off","native-on","fast-native","quality-scene","limited-on","dawn-motion","spatial-on","temporal-on","inventory",
            "settings-off","settings-on","pause","odd-resize","5k-output","fullscreen-on","camera-motion","minimize-restore",
            "vsync-off","vsync-return","off-return","menu-on"};
    public static FrameGenerationCoordinator presentedOwner;
    public static boolean controlledSettings;
    private static int stage=-1,frames,failures;
    private static long started=System.nanoTime(),stageStart,initialGenerated,initialCaptures,initialIntervals,initialIntervalSum,initialShort,initialOrdering,initialReal,initialDisplayTime;
    private static boolean restored,done,drainSampled;
    private static net.minecraft.client.CloudStatus qualitySceneClouds;
    private static long bootstrapVisibleSince;
    private static boolean bootstrapPrepared;
    private static Path root;
    private static final List<String> checks=new ArrayList<>();
    private static MethodHandle activate,visibility;
    public static void frame(Minecraft mc) {
        if(!Boolean.getBoolean("metalmod.frameGenerationValidation")||done)return;
        try { run(mc); }
        catch(Throwable error) {error.printStackTrace();check("runtime",false,error.toString());finish(mc);}
    }
    private static void check(String name,boolean value,String detail) {
        checks.add((value?"PASS ":"FAIL ")+name+" "+detail);if(!value)failures++;
        System.out.println("[FG validation] "+checks.getLast());
    }
    private static void run(Minecraft mc) throws Throwable {
        if(root==null){
            root=mc.gameDirectory.toPath().resolve(Boolean.getBoolean("metalmod.frameGenerationRestartValidation")?"validation-restart":"validation");Files.createDirectories(root);
            if(!Files.exists(mc.gameDirectory.toPath().resolve(".metalmod-benchmark-copy")))throw new IllegalStateException("copy marker missing");
        }
        long now=System.nanoTime();
        if(now-started>300_000_000_000L){check("bounded runtime",false,"timeout");finish(mc);return;}
        if(stage<0){
            if(mc.level==null||mc.player==null||!mc.isGameLoadFinished())return;
            if(!bootstrapPrepared) {
                mc.options.pauseOnLostFocus=false;mc.options.inactivityFpsLimit().set(InactivityFpsLimit.MINIMIZED);
                mc.options.framerateLimit().set(60);mc.options.enableVsync().set(true);
                GLFW.glfwShowWindow(mc.getWindow().handle());GLFW.glfwRestoreWindow(mc.getWindow().handle());
                bootstrapPrepared=true;
            }
            focus(mc);
            // Cocoa activation is asynchronous. An occluded startup cannot measure display
            // delivery; start the fixture only after the owned test window is stably visible.
            long visible=(long)visibility.invokeExact();
            if((visible&7)!=7) {bootstrapVisibleSince=0;return;}
            if(bootstrapVisibleSince==0)bootstrapVisibleSince=now;
            if(now-bootstrapVisibleSince<1_000_000_000L)return;
            next(mc);return;
        }
        if(!STAGES[stage].equals("menu-on")&&(mc.level==null||mc.player==null||!mc.isGameLoadFinished()))return;
        frames++;
        if(!STAGES[stage].equals("minimize-restore") && frames%30==0) {
            if(STAGES[stage].equals("menu-on"))GLFW.glfwShowWindow(mc.getWindow().handle());
            focus(mc);
        }
        if(Set.of("camera-motion","dawn-motion","limited-native-camera","limited-spatial-camera").contains(STAGES[stage])) {mc.player.setYRot(mc.player.getYRot()+0.15f);mc.player.setYHeadRot(mc.player.getYRot());}
        if(STAGES[stage].equals("quality-scene")&&frames%10==0) {
            var server=mc.getSingleplayerServer();double px=mc.player.getX(),py=mc.player.getY(),pz=mc.player.getZ();
            double x=px+Math.sin(frames*0.04)*3,y=py+4,z=pz-7;
            server.execute(()->{
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"tp @e[type=minecraft:phantom,tag=MetalModFGQuality,limit=1] "+x+" "+y+" "+z);
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"summon minecraft:experience_orb "+px+" "+(py+1)+" "+(pz-2)+" {Value:3}");
            });
        }
        if(STAGES[stage].equals("dawn-motion")&&frames%10==0) {
            var server=mc.getSingleplayerServer();int time=23000+frames*8;
            if(server!=null)server.execute(()->server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"time set "+time));
        }
        if(STAGES[stage].equals("minimize-restore")&&!restored&&now-stageStart>1_000_000_000L){
            GLFW.glfwRestoreWindow(mc.getWindow().handle());focus(mc);restored=true;
        }
        if(!drainSampled&&now-stageStart>1_000_000_000L) {
            if(Set.of("native-off","settings-off","vsync-off","off-return","menu-on").contains(STAGES[stage]))
                initialGenerated=FrameGenerationCoordinator.stats().generatedDisplayed();
            var sample=FrameGenerationCoordinator.stats();
            initialIntervals=sample.intervals();initialIntervalSum=sample.intervalSumNs();
            initialShort=sample.shortIntervals();initialOrdering=sample.orderingErrors();
            initialReal=sample.realDisplayed();initialDisplayTime=sample.presentedTimeNs();
            drainSampled=true;
        }
        if(now-stageStart<5_000_000_000L||frames<90)return;
        var s=FrameGenerationCoordinator.stats();String name=STAGES[stage];
        long visible=(long)visibility.invokeExact();
        check(name+" test window onscreen",(visible&6)==6,"visibility bits="+visible+" focus="+mc.isWindowActive());
        boolean expected=!Set.of("native-off","settings-off","vsync-off","off-return","menu-on").contains(name);
        check(name+" render loop responsive",frames>=90,"frames="+frames);
        check(name+" no interpolation failure",!s.failed(),s.toString());
        if(Set.of("fast-native","quality-scene").contains(name)) {
            check(name+" preserves full-rate real rendering",frames*1e9/(now-stageStart)>=48,"real FPS="+frames*1e9/(now-stageStart));
            check(name+" does not manufacture doubled frames in a light scene",s.generatedDisplayed()==0,s.toString());
        } else if(expected&&FrameGenerationCoordinator.qualityProtected()) {
            check(name+" rejected unreliable interpolation without GPU failure",!s.failed(),FrameGenerationSettings.status().reason());
            check(name+" real rendering remains responsive after quality rejection",frames>=90,"");
        } else if(expected) {
            check(name+" eligible midpoint encoded",s.eligible()>20,s.toString());
            check(name+" generated actually displayed",s.generatedDisplayed()>initialGenerated+10,s.toString());
            check(name+" real actually displayed",s.realDisplayed()>10&&s.presentedTimeNs()>0,s.toString());
            if(!name.equals("minimize-restore")) {
                long intervals=s.intervals()-initialIntervals,sum=s.intervalSumNs()-initialIntervalSum;
                double displayFps=sum>0?intervals*1e9/sum:0;
                double renderedFps=initialDisplayTime>0?(s.realDisplayed()-initialReal)*1e9/Math.max(1,s.presentedTimeNs()-initialDisplayTime)
                    :s.realDisplayed()*1e9/Math.max(1,s.presentedTimeNs()-s.firstPresentedTimeNs());
                check(name+" separate VSync display times",s.shortIntervals()==initialShort,s.toString());
                check(name+" generated/real display order",s.orderingErrors()==initialOrdering,s.toString());
                check(name+" displayed FPS doubles paired real FPS",displayFps>renderedFps*1.9&&displayFps<renderedFps*2.1,
                    "display FPS="+displayFps+" paired real FPS="+renderedFps);
                check(name+" display meets 60Hz cadence",displayFps>=48&&displayFps<=65,"display FPS="+displayFps);
            }
        } else check(name+" no generated display activity",s.generatedDisplayed()==initialGenerated,s.toString());
        if(name.equals("native-on"))check("independent of Super Resolution",!UpscalingSettings.current().enabled(),MetalFxCoordinator.stats().toString());
        if(name.equals("spatial-on"))check("spatial plus interpolation",MetalFxCoordinator.stats().effective().equals("MetalFX spatial"),MetalFxCoordinator.stats().summary());
        if(name.equals("temporal-on"))check("FG does not interpolate temporal history a second time",MetalFxCoordinator.stats().effective().equals("MetalFX spatial"),MetalFxCoordinator.stats().summary());
        if(name.equals("settings-on")){MetalConfigReload.check(checks);}
        if(name.startsWith("limited-")&&name.endsWith("-camera")) {
            check(name+" moving world interpolation remains active",!FrameGenerationCoordinator.qualityProtected(),FrameGenerationSettings.status().reason());
        }
        if(name.equals("quality-scene"))checkRasterCoverage(mc);
        if(expected)captureImages(mc,name);
        Files.writeString(root.resolve(name+".txt"),s+"\n"+MetalFxCoordinator.stats()+"\nframes="+frames+"\n");
        next(mc);
    }
    private static void checkRasterCoverage(Minecraft mc) throws Throwable {
        check("entity coverage fixture excludes cloud raster coverage",
                mc.options.getCloudStatus()==net.minecraft.client.CloudStatus.OFF, "");
        var owner=presentedOwner;check("quality scene has world capture",owner!=null,"");if(owner==null)return;
        var field=FrameGenerationCoordinator.class.getDeclaredField("handle");field.setAccessible(true);
        var coverage=net.metalmod.backend.MetalNative.frameGenerationCoverage((MemorySegment)field.get(owner));
        var targetField=mc.gameRenderer.getClass().getDeclaredField("mainRenderTarget");targetField.setAccessible(true);
        var target=(com.mojang.blaze3d.pipeline.RenderTarget)targetField.get(mc.gameRenderer);
        net.metalmod.backend.MetalNative.queueSynchronize(MetalDevice.active().queueHandle());
        try(var arena=Arena.ofConfined()) {
            int total=target.width*target.height;var pixels=arena.allocate(total);
            int rc=net.metalmod.backend.MetalNative.textureReadRegion(coverage,0,0,0,0,target.width,target.height,pixels,total,target.width);
            int covered=0;for(int i=0;i<total;i++)if(pixels.getAtIndex(ValueLayout.JAVA_BYTE,i)!=0)covered++;
            check("phantom/orb/beam coverage is raster-sized rather than surrounding influence regions",rc==0&&covered>10&&covered<total/3,"covered="+covered+" total="+total);
        }
        var motionField=FrameGenerationCoordinator.class.getDeclaredField("motion");motionField.setAccessible(true);
        check("experience orb cannot assign motion to nearby terrain",((TemporalSceneMotion)motionField.get(owner)).count()==0,"");
    }
    private static void captureImages(Minecraft mc,String name) throws Throwable {
        var targetField=mc.gameRenderer.getClass().getDeclaredField("mainRenderTarget");targetField.setAccessible(true);
        var target=(com.mojang.blaze3d.pipeline.RenderTarget)targetField.get(mc.gameRenderer);
        var real=(net.metalmod.backend.MetalTexture)target.getColorTexture();
        var owner=presentedOwner;if(owner==null)return;
        boolean hasGenerated=!FrameGenerationCoordinator.qualityProtected()&&FrameGenerationCoordinator.stats().encodes()>0;
        var field=FrameGenerationCoordinator.class.getDeclaredField("handle");field.setAccessible(true);
        var texture=Linker.nativeLinker().downcallHandle(SymbolLookup.loaderLookup().find("mmm_fg_texture").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS,ValueLayout.ADDRESS));
        var generated=(MemorySegment)texture.invokeExact((MemorySegment)field.get(owner));
        net.metalmod.backend.MetalNative.queueSynchronize(MetalDevice.active().queueHandle());
        if(Boolean.getBoolean("metalmod.frameGenerationDeliveryValidation")) {
            var debug=Linker.nativeLinker().downcallHandle(SymbolLookup.loaderLookup().find("mmm_fg_debug_texture").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,ValueLayout.ADDRESS,ValueLayout.JAVA_INT));
            int iw=MetalFxCoordinator.stats().sceneWidth(),ih=MetalFxCoordinator.stats().sceneHeight();
            Files.writeString(root.resolve(name+"-dimensions.txt"),target.width+" "+target.height+" "+iw+" "+ih+"\n");
            for(int role=0;role<8;role++)try(var diagnostic=Arena.ofConfined()) {
                var t=(MemorySegment)debug.invokeExact((MemorySegment)field.get(owner),role);
                int w=role==5||role==6?iw:target.width,h=role==5||role==6?ih:target.height;
                int stride=role<3?8:role==3||role==5?4:1;
                var data=diagnostic.allocate((long)w*h*stride);
                long format=role<3?115L:role==3?net.metalmod.backend.MetalFormat.mtlPixelFormat(real.getFormat()):role==5?65L:10L;
                var staging=net.metalmod.backend.MetalNative.textureCreateFull(MetalDevice.active().deviceHandle(),format,w,h,1,1,2,true,1);
                try {
                    int copied=net.metalmod.backend.MetalNative.copyTextureToTexture(MetalDevice.active().queueHandle(),t,0,0,0,0,staging,0,0,0,0,w,h,1);
                    net.metalmod.backend.MetalNative.utilityEndFrame();
                    net.metalmod.backend.MetalNative.queueSynchronize(MetalDevice.active().queueHandle());
                    int rc=net.metalmod.backend.MetalNative.textureReadRegion(staging,0,0,0,0,w,h,data,data.byteSize(),(long)w*stride);
                    if(copied!=0||rc!=0)throw new IllegalStateException("diagnostic texture readback "+role+" "+rc);
                } finally {net.metalmod.backend.MetalNative.textureRelease(staging);}
                Files.write(root.resolve(name+"-diagnostic-"+role+".bin"),data.toArray(ValueLayout.JAVA_BYTE));
            }
        }
        for(int role=0;role<(hasGenerated?2:1);role++) {
            try(var arena=Arena.ofConfined();var image=new com.mojang.blaze3d.platform.NativeImage(target.width,target.height,false)) {
                var pixels=arena.allocate((long)target.width*target.height*4,4);
                int rc=net.metalmod.backend.MetalNative.textureReadRegion(role==0?real.handle():generated,0,0,0,0,
                        target.width,target.height,pixels,pixels.byteSize(),target.width*4L);
                if(rc!=0)throw new IllegalStateException("test image readback "+rc);
                boolean bgra=net.metalmod.backend.MetalFormat.mtlPixelFormat(real.getFormat())==80;
                for(int y=0;y<target.height;y++)for(int x=0;x<target.width;x++) {
                    long offset=((long)y*target.width+x)*4;
                    int r=pixels.get(ValueLayout.JAVA_BYTE,offset+(bgra?2:0))&255;
                    int g=pixels.get(ValueLayout.JAVA_BYTE,offset+1)&255;
                    int b=pixels.get(ValueLayout.JAVA_BYTE,offset+(bgra?0:2))&255;
                    int a=pixels.get(ValueLayout.JAVA_BYTE,offset+3)&255;
                    image.setPixel(x,y,(a<<24)|(r<<16)|(g<<8)|b);
                }
                if(role==1&&Set.of("native-on","temporal-on","camera-motion","dawn-motion","quality-scene").contains(name)) {
                    var lookup=SymbolLookup.loaderLookup();var linker=Linker.nativeLinker();
                    var descriptor=FunctionDescriptor.of(ValueLayout.ADDRESS,ValueLayout.ADDRESS);
                    var mask=(MemorySegment)linker.downcallHandle(lookup.find("mmm_fg_hand_mask").orElseThrow(),descriptor)
                        .invokeExact((MemorySegment)field.get(owner));
                    var ui=(MemorySegment)linker.downcallHandle(lookup.find("mmm_fg_ui_texture").orElseThrow(),descriptor)
                        .invokeExact((MemorySegment)field.get(owner));
                    var maskPixels=arena.allocate((long)target.width*target.height,1);
                    var uiPixels=arena.allocate((long)target.width*target.height*8,2);
                    int mr=net.metalmod.backend.MetalNative.textureReadRegion(mask,0,0,0,0,target.width,target.height,
                        maskPixels,maskPixels.byteSize(),target.width);
                    int ur=net.metalmod.backend.MetalNative.textureReadRegion(ui,0,0,0,0,target.width,target.height,
                        uiPixels,uiPixels.byteSize(),target.width*8L);
                    check(name+" native hand coverage readback",mr==0&&ur==0,"");
                    int sampled=0,exact=0;
                    for(int y=8;y<target.height-8;y+=4)for(int x=8;x<target.width-8;x+=4) {
                        boolean inside=true;
                        for(int dy=-8;dy<=8;dy+=8)for(int dx=-8;dx<=8;dx+=8)
                            inside &= (maskPixels.get(ValueLayout.JAVA_BYTE,(long)(y+dy)*target.width+x+dx)&255)==255;
                        if(!inside)continue;
                        sampled++;long offset=((long)y*target.width+x)*8;int pixel=image.getPixel(x,y);
                        boolean same=true;
                        for(int c=0;c<3;c++) {
                            float linear=Float.float16ToFloat(uiPixels.get(ValueLayout.JAVA_SHORT,offset+c*2));
                            double value=linear<=0.0031308?linear*12.92:1.055*Math.pow(Math.max(0,linear),1.0/2.4)-0.055;
                            int expected=(int)Math.round(Math.clamp(value,0.0,1.0)*255);
                            same &= Math.abs(((pixel>>(16-c*8))&255)-expected)<=2;
                        }
                        if(same)exact++;
                    }
                    check(name+" generated hand preserves native opacity/colour",sampled>100&&exact>=sampled*0.98,
                        "protected interior pixels="+sampled+" exact colour="+exact);
                }
                image.writeToFile(root.resolve(name+(role==0?"-real.png":"-generated.png")));
            }
        }
    }
    private static void next(Minecraft mc) throws Throwable {
        if(qualitySceneClouds!=null) {
            mc.options.cloudStatus().set(qualitySceneClouds);
            check("entity coverage fixture restores cloud setting",mc.options.cloudStatus().get()==qualitySceneClouds, "");
            qualitySceneClouds=null;
        }
        if(++stage==STAGES.length){finish(mc);return;}
        String name=STAGES[stage];controlledSettings=false;mc.gui.setScreen(null);
        if(name.equals("startup-on"))check("saved On active at fresh JVM startup",FrameGenerationSettings.current().enabled(),"");
        if(name.equals("fullscreen-on"))mc.options.fullscreen().set(true);
        if(name.equals("5k-output"))GLFW.glfwSetWindowSize(mc.getWindow().handle(),2560,1440);
        if(name.startsWith("limited-")&&name.endsWith("-camera")) {
            System.setProperty("metalmod.frameGenerationForce","false");mc.options.framerateLimit().set(30);
            UpscalingSettings.chooseEnabled(name.contains("spatial"));UpscalingSettings.chooseStrength(50);TemporalJitterProof.chooseEnabled(false);
            FrameGenerationSettings.chooseEnabled(false);FrameGenerationSettings.chooseEnabled(true);
        }
        if(name.equals("native-off")){UpscalingSettings.chooseEnabled(false);TemporalJitterProof.chooseEnabled(false);FrameGenerationSettings.chooseEnabled(false);}
        if(name.equals("native-on"))FrameGenerationSettings.chooseEnabled(true);
        if(name.equals("fast-native")||name.equals("quality-scene")) {
            System.setProperty("metalmod.frameGenerationForce","false");mc.options.framerateLimit().set(60);
            FrameGenerationSettings.chooseEnabled(false);FrameGenerationSettings.chooseEnabled(true);
        }
        if(name.equals("quality-scene")) {
            // This check bounds phantom/orb/beam coverage. Clouds legitimately write the same
            // reactive mask and can exceed its area limit; isolate the fixture, then restore
            // the user's copied cloud setting for all subsequent presentation stages.
            qualitySceneClouds=mc.options.cloudStatus().get();
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
            double x=mc.player.getX(),y=mc.player.getY(),z=mc.player.getZ();
            mc.player.setYRot(180);mc.player.setXRot(-10);
            var server=mc.getSingleplayerServer();
            server.execute(()->{
                var source=server.createCommandSourceStack();var commands=server.getCommands();
                commands.performPrefixedCommand(source,"summon minecraft:phantom "+(x+2)+" "+(y+4)+" "+(z-7)+" {NoAI:1b,Tags:[\"MetalModFGQuality\"]}");
                commands.performPrefixedCommand(source,"fill "+((int)x-4)+" "+((int)y-2)+" "+((int)z-8)+" "+((int)x-2)+" "+((int)y-2)+" "+((int)z-6)+" minecraft:iron_block");
                commands.performPrefixedCommand(source,"fill "+((int)x-3)+" "+(int)y+" "+((int)z-7)+" "+((int)x-3)+" "+((int)y+40)+" "+((int)z-7)+" minecraft:air");
                commands.performPrefixedCommand(source,"setblock "+((int)x-3)+" "+((int)y-1)+" "+((int)z-7)+" minecraft:beacon");
            });
        }
        if(name.equals("limited-on")){System.setProperty("metalmod.frameGenerationForce","false");mc.options.framerateLimit().set(30);}
        if(name.equals("dawn-motion")){System.setProperty("metalmod.frameGenerationForce","true");mc.options.framerateLimit().set(60);}

        if(name.equals("spatial-on")){UpscalingSettings.chooseEnabled(true);UpscalingSettings.chooseStrength(50);}
        if(name.equals("temporal-on"))TemporalJitterProof.chooseEnabled(true);
        if(name.equals("inventory"))mc.gui.setScreen(new InventoryScreen(mc.player));
        if(name.startsWith("settings-")){
            mc.gui.setScreen(new MetalModMetalFxScreen(null));
            var button=mc.gui.screen().children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().startsWith("Frame Generation:"))
                    .map(c->(Button)c).findFirst().orElseThrow();
            check(name+" actual UI toggle enabled",button.active,button.getMessage().getString());
            button.onPress(null);
            check(name+" requested value",FrameGenerationSettings.current().enabled()==name.endsWith("on"),button.getMessage().getString());
            controlledSettings=true;button.active=false;
        }
        if(name.equals("pause"))mc.gui.setScreen(new PauseScreen(true));
        if(name.equals("odd-resize"))GLFW.glfwSetWindowSize(mc.getWindow().handle(),1281,721);
        if(name.equals("minimize-restore"))GLFW.glfwIconifyWindow(mc.getWindow().handle());
        if(name.equals("vsync-off"))mc.options.enableVsync().set(false);
        if(name.equals("vsync-return"))mc.options.enableVsync().set(true);
        if(name.equals("off-return"))FrameGenerationSettings.chooseEnabled(false);
        if(name.equals("menu-on")){FrameGenerationSettings.chooseEnabled(true);mc.options.fullscreen().set(false);mc.disconnect(new TitleScreen(),false);GLFW.glfwShowWindow(mc.getWindow().handle());focus(mc);}
        initialGenerated=FrameGenerationCoordinator.stats().generatedDisplayed();initialCaptures=FrameGenerationCoordinator.stats().captures();
        // On transitions recreate counters; compare within-generation display counts after the first second.
        if(name.startsWith("limited-")&&name.endsWith("-camera")||name.equals("native-on")||name.equals("spatial-on")||name.equals("temporal-on")||name.equals("settings-on")||name.equals("odd-resize")||name.equals("5k-output")||name.equals("fullscreen-on"))initialGenerated=0;
        stageStart=System.nanoTime();frames=0;restored=false;drainSampled=false;
        initialIntervals=initialIntervalSum=initialShort=initialOrdering=initialReal=initialDisplayTime=0;
        System.out.println("[FG validation] stage "+name);
    }
    private static void focus(Minecraft mc) throws Throwable {
        if(activate==null){System.load(System.getProperty("metalmod.validationFocus"));activate=Linker.nativeLinker().downcallHandle(
                SymbolLookup.loaderLookup().find("metalmod_validation_activate").orElseThrow(),FunctionDescriptor.ofVoid());}
        if(visibility==null)visibility=Linker.nativeLinker().downcallHandle(SymbolLookup.loaderLookup()
                .find("metalmod_validation_visibility").orElseThrow(),FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        activate.invokeExact();GLFW.glfwFocusWindow(mc.getWindow().handle());
    }
    private static void finish(Minecraft mc) {
        if(done)return;done=true;
        try {Files.writeString(root.resolve("checks.txt"),String.join("\n",checks)+"\n");
            Files.writeString(root.resolve("result.txt"),(failures==0?"PASS\n":"FAIL\n")+"checks="+checks.size()+" failures="+failures+"\n");}
        catch(Exception e){throw new RuntimeException(e);}mc.stop();
    }
    private static final class MetalConfigReload {
        static void check(List<String> ignored) {net.metalmod.config.MetalConfig.INSTANCE.load();
            FrameGenerationValidation.check("Frame Generation On persisted",net.metalmod.config.MetalConfig.INSTANCE.enableFrameGeneration,"");}
    }
}
