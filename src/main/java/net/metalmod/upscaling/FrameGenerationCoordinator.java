package net.metalmod.upscaling;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalFormat;
import net.metalmod.backend.MetalNative;
import net.metalmod.backend.MetalTexture;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Matrix4fc;

/** Real-render-owned interpolation; world capture precedes native hand/HUD, present consumes once. */
public final class FrameGenerationCoordinator implements AutoCloseable {
    public record Stats(long captures,long encodes,long eligible,long generatedDisplayed,long realDisplayed,
                        long dropped,boolean failed,long presentedTimeNs,long firstPresentedTimeNs,
                        long intervals,long intervalSumNs,long minIntervalNs,long maxIntervalNs,
                        long shortIntervals,long missedRefreshes,long orderingErrors,long displayedFpsMilli) {
        public double displayedFps(){return displayedFpsMilli/1000.0;}
    }
    private static volatile Stats stats=new Stats(0,0,0,0,0,0,false,0,0,0,0,0,0,0,0,0,0);
    public static Stats stats(){return stats;}
    private static FrameGenerationCoordinator pending;
    private static boolean qualityProtected;
    public static boolean qualityProtected(){return qualityProtected;}
    private static boolean fifo;
    private static FrameGenerationCoordinator rasterOwner;
    private com.mojang.blaze3d.textures.GpuTexture rasterTarget;
    private boolean interpolationCommitted,rasterGui;
    private final FrameGenerationCadence cadence=new FrameGenerationCadence();
    public static void surfaceMode(boolean enabled){fifo=enabled;}
    private record Key(long device,int iw,int ih,int ow,int oh,long format,long revision) {}
    private final TemporalSceneMotion motion=new TemporalSceneMotion(false);
    private MemorySegment handle=MemorySegment.NULL;
    private MetalDevice device;
    private Key key;
    private boolean world,projectionCaptured,failed,reset;
    private long frameId,lastCaptureTime,serial;
    private float near=0.05f,far=65536,fov=70;

    public void begin(boolean world,long serial) {
        if(pending==this)pending=null;
        this.serial=serial;projectionCaptured=false;
        if(rasterOwner==this)rasterOwner=null;
        this.world=world&&FrameGenerationSettings.current().enabled()&&fifo&&MetalDevice.active()!=null;
        if(!this.world) {
            cadence.reset();interpolationCommitted=false;
            motion.clear("no generated world");lastCaptureTime=0;
            if(!FrameGenerationSettings.current().enabled()) {close();FrameGenerationSettings.publish(false,"Off");}
            else FrameGenerationSettings.publish(false,MetalDevice.active()==null?"Metal renderer required"
                    :!fifo?"enable VSync for frame generation":"waiting for world");
        }
    }
    public void projection(Matrix4fc projection,LevelRenderState level) {
        if(!world)return;
        reset=motion.capture(projection,level,serial);projectionCaptured=true;
        near=Math.abs(projection.m32()/(1+projection.m22()));
        far=Math.abs(projection.m22())<1e-7f?65536:Math.abs(projection.m32()/projection.m22());
        if(!Float.isFinite(near)||near<=0)near=0.05f;
        if(!Float.isFinite(far)||far<=near)far=65536;
        fov=(float)Math.toDegrees(2*Math.atan(1/Math.abs(projection.m11())));
        if(!motion.validInputs()||!Float.isFinite(fov)||fov<=0||fov>=180) {
            projectionCaptured=false;motion.clear("waiting for valid camera");lastCaptureTime=0;
            FrameGenerationSettings.publish(false,"waiting for valid camera");
        }
    }
    /** Allocate/clear exact visible-fragment coverage before any world draw. */
    public void prepare(RenderTarget output,RenderTarget input) {
        if(!world)return;
        var active=MetalDevice.active();var preference=FrameGenerationSettings.current();
        var next=new Key(active.deviceHandle().address(),input.width,input.height,output.width,output.height,
                MetalFormat.mtlPixelFormat(output.getColorTexture().getFormat()),preference.revision());
        if(!next.equals(key)) {
            retire();key=next;device=active;failed=false;reset=true;lastCaptureTime=0;
            handle=MetalNative.frameGenerationCreate(active.deviceHandle(),active.queueHandle(),next.iw,next.ih,next.ow,next.oh,next.format);
            if(handle.address()==0)fail("MetalFX interpolation unsupported or setup failed");
            else System.out.println("[MetalMod] Frame Generation ready: "+next.iw+"x"+next.ih+" -> "+next.ow+"x"+next.oh);
        }
        if(failed)return;
        rasterTarget=input.getColorTexture();rasterOwner=this;rasterGui=false;
        int rc=MetalNative.clearTextures(device.queueHandle(),MetalNative.frameGenerationCoverage(handle),
                true,0,0,0,0,MemorySegment.NULL,false,0);
        if(rc!=0)fail("world coverage clear failed "+rc);
    }
    public static MemorySegment coverageFor(com.mojang.blaze3d.textures.GpuTexture target) {
        var owner=rasterOwner;
        return owner!=null&&!owner.failed&&owner.rasterTarget==target
                ?owner.rasterGui?MetalNative.frameGenerationGuiCoverage(owner.handle):MetalNative.frameGenerationCoverage(owner.handle):MemorySegment.NULL;
    }
    public static boolean guiRasterActive(){return rasterOwner!=null&&rasterOwner.rasterGui;}
    public static boolean rasterActive(){return rasterOwner!=null;}
    public void capture(RenderTarget output,RenderTarget input,float jitterX,float jitterY) {
        if(!world||!projectionCaptured)return;
        if(failed||handle.address()==0)return;
        long now=System.nanoTime();float dt=lastCaptureTime==0?1/60f:(now-lastCaptureTime)/1_000_000_000f;
        cadence.sample(dt);reset|=!interpolationCommitted;
        if(dt>0.25f||dt<=0){dt=1/60f;reset=true;}
        MemorySegment cb=MetalNative.commandBufferCreate(device.queueHandle());
        try(var arena=Arena.ofConfined()) {
            int rc=MetalNative.frameGenerationCapture(handle,cb,((MetalTexture)output.getColorTexture()).handle(),
                    ((MetalTexture)input.getColorTexture()).handle(),((MetalTexture)input.getDepthTexture()).handle(),
                    motion.matrices(arena),motion.objects(arena),motion.count(),++frameId,dt,near,far,fov,jitterX,jitterY,reset);
            if(rc==-4) {
                motion.clear("invalid camera/object snapshot");lastCaptureTime=0;
                FrameGenerationSettings.publish(false,"waiting for valid world inputs");return;
            }
            if(rc<0){fail("capture failed "+rc);return;}
            MetalDevice.countCommandBuffer();MetalNative.commandBufferCommit(cb);motion.committed();
            lastCaptureTime=now;pending=this;if(rasterOwner==this)rasterOwner=null;publish();
        } catch(RuntimeException error){fail("capture exception: "+error.getMessage());}
        finally{MetalNative.commandBufferRelease(cb);}
    }
    /** Native hand depth still contains geometry here; the GUI clears it immediately afterward. */
    public void captureHand(RenderTarget output) {
        if(pending!=this||failed||handle.address()==0)return;
        var cb=MetalNative.commandBufferCreate(device.queueHandle());
        try {
            int rc=MetalNative.frameGenerationCaptureHand(handle,cb,((MetalTexture)output.getDepthTexture()).handle(),((MetalTexture)output.getColorTexture()).handle());
            if(rc<0){fail("hand coverage failed "+rc);return;}
            MetalDevice.countCommandBuffer();MetalNative.commandBufferCommit(cb);
            rasterTarget=output.getColorTexture();rasterOwner=this;rasterGui=true;
        } finally {MetalNative.commandBufferRelease(cb);}
    }
    /** Called only by the ordinary surface owner. True means its retained drawable was consumed. */
    public static boolean present(MemorySegment layer,MemorySegment drawable,MemorySegment real) {
        var owner=pending;pending=null;if(rasterOwner==owner)rasterOwner=null;
        if(owner==null||owner.failed||!fifo||!FrameGenerationSettings.current().enabled()||real.address()==0)return false;
        var mc=net.minecraft.client.Minecraft.getInstance();
        long monitor=org.lwjgl.glfw.GLFW.glfwGetWindowMonitor(mc.getWindow().handle());
        if(monitor==0)monitor=org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor();
        var mode=monitor==0?null:org.lwjgl.glfw.GLFW.glfwGetVideoMode(monitor);
        int refresh=mode==null||mode.refreshRate()<=0?60:Math.clamp(mode.refreshRate(),30,240);
        int renderLimit=mc.options.framerateLimit().get();
        boolean deliberatelyLimited=renderLimit>0&&renderLimit<=refresh/2;
        if(!Boolean.getBoolean("metalmod.frameGenerationForce")&&!owner.cadence.generate(1.0/refresh,!deliberatelyLimited)) {
            owner.interpolationCommitted=false;owner.publish();
            FrameGenerationSettings.publish(false,"real-frame cadence retained; no interpolation needed");return false;
        }
        owner.interpolationCommitted=true;
        int rc=MetalNative.frameGenerationPresent(owner.handle,layer,drawable,real,1f/refresh);
        owner.publish();
        if(stats.failed){owner.fail("GPU/presentation failed; ordinary frames");}
        return rc==0;
    }
    private void publish() {
        long[] s=MetalNative.frameGenerationStats(handle);
        stats=new Stats(s[0],s[1],s[2],s[3],s[4],s[5],(s[6]&1)!=0,s[7],s[8],s[9],s[10],s[11],s[12],s[13],s[14],s[15],s[16]);
        qualityProtected=(s[6]&2)!=0;
        FrameGenerationSettings.publish(!stats.failed&&!qualityProtected,qualityProtected?"interpolation quality protection; native real frames":stats.failed?"failed; ordinary frames":s[2]>0
                ? String.format(java.util.Locale.ROOT,"Display %.1f FPS | generated %d / real %d",stats.displayedFps(),s[3],s[4]) : "warming interpolation history");
    }
    private void fail(String reason){failed=true;
        var old=stats;stats=new Stats(old.captures,old.encodes,old.eligible,old.generatedDisplayed,
                old.realDisplayed,old.dropped,true,old.presentedTimeNs,old.firstPresentedTimeNs,old.intervals,old.intervalSumNs,
                old.minIntervalNs,old.maxIntervalNs,old.shortIntervals,old.missedRefreshes,old.orderingErrors,old.displayedFpsMilli);
        motion.clear("generation failed");if(pending==this)pending=null;
        FrameGenerationSettings.publish(false,reason);System.err.println("[MetalMod] Frame Generation: "+reason);}
    public void invalidate(){retire();key=null;motion.clear("resource change");lastCaptureTime=0;}
    private void retire(){
        if(pending==this)pending=null;
        if(rasterOwner==this)rasterOwner=null;interpolationCommitted=false;cadence.reset();qualityProtected=false;
        if(handle.address()!=0){MetalNative.queueSynchronize(device.queueHandle());MetalNative.frameGenerationRelease(handle);}
        handle=MemorySegment.NULL;device=null;
    }
    @Override public void close(){retire();key=null;motion.clear("Off");lastCaptureTime=0;}
}
