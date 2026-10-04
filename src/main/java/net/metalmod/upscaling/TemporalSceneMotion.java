package net.metalmod.upscaling;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.Map;

/** Render-time history with explicit camera-relative origins and one-frame object identity. */
public final class TemporalSceneMotion {
    public record Stats(long resets, String reason, int objects, long frames, int lightRegions) {}
    private static volatile Stats stats=new Stats(0,"first frame",0,0,0);
    public static Stats stats() { return stats; }
    private static final java.util.Set<TemporalSceneMotion> active=new java.util.HashSet<>();
    private record Position(double x,double y,double z) {}
    private record MovingId(long block, Object type) {}
    private static final int CAPACITY=1024;
    private final boolean trackObjects;
    public TemporalSceneMotion(){this(true);}
    public TemporalSceneMotion(boolean trackObjects){this.trackObjects=trackObjects;}
    private final float[] objects=new float[CAPACITY*12];
    private final Matrix4f currentPV=new Matrix4f(), previousPV=new Matrix4f(), inverse=new Matrix4f();
    private final Matrix4f reprojection=new Matrix4f(), lastProjection=new Matrix4f(), lastView=new Matrix4f();
    private Map<Object,Position> previous=new HashMap<>(), current=new HashMap<>();
    private java.util.Set<net.metalmod.lighting.PointLight> previousLights=java.util.Set.of(),currentLights=java.util.Set.of();
    private Vec3 origin, previousOrigin;
    private Object world;
    private long serial,lastSerial=-1,lastTime,frames,resets;
    private int count,lightRegions;
    private boolean reset=true, valid, paused;
    private String reason="first frame";

    public void clear(String why) {
        valid=false;previous.clear();current.clear();previousLights=currentLights=java.util.Set.of();count=0;
        reason=why;reset=true;active.remove(this);
    }
    /** Called before choosing this frame's jitter. True means restart the sample sequence. */
    public boolean capture(Matrix4fc projection, LevelRenderState level, long frameSerial) {
        var mc=Minecraft.getInstance();var camera=level.cameraRenderState;
        long now=System.nanoTime();boolean nowPaused=mc.isPaused();
        String why=!valid?reason:mc.level!=world?"world change":frameSerial!=lastSerial+1?"missing frame"
                :now-lastTime>250_000_000L?"render gap":nowPaused!=paused?"pause transition"
                :previousOrigin.distanceTo(camera.pos)>8?"camera cut"
                :Math.abs(lastProjection.m00()/projection.m00()-1)>0.1f
                    ||Math.abs(lastProjection.m11()/projection.m11()-1)>0.1f?"projection change"
                :rotationDistance(lastView,camera.viewRotationMatrix)>1.5f?"camera turn":"";
        reset=!why.isEmpty();if(reset){previous.clear();reason=why;resets++;}
        world=mc.level;origin=camera.pos;serial=frameSerial;lastTime=now;paused=nowPaused;
        currentPV.set(projection).mul(camera.viewRotationMatrix);currentPV.invert(inverse);
        if(!inverse.isFinite()) { reset=true;reason="invalid projection";previous.clear(); }
        if(reset)reprojection.identity();
        else reprojection.set(previousPV).translate((float)(origin.x-previousOrigin.x),
                (float)(origin.y-previousOrigin.y),(float)(origin.z-previousOrigin.z)).mul(inverse);
        current.clear();count=0;active.add(this);
        if(trackObjects)for(var entity:level.entityRenderStates) {
            Object id=entity instanceof TemporalEntityIdentity identity?identity.metalmod$temporalIdentity():null;
            if(id!=null)net.metalmod.Diagnostics.hook("Temporal.entityIdentity");
            // Unknown states remain rejectable rather than inheriting another entity's transform.
            double radius=Math.max(0.25,entity.boundingBoxWidth*0.75+0.25);
            add(id,entity.x,entity.y,entity.z,radius,Math.max(0.5,entity.boundingBoxHeight)+0.5);
        }
        currentLights=trackObjects&&net.metalmod.lighting.LightingSettings.active()
                ? new java.util.HashSet<>(net.metalmod.lighting.LightCollector.current().lights()):java.util.Set.of();
        // New, moving, recoloured and removed lights reject history over both influence regions.
        // This observes Phase 6's published world-space snapshot without changing its ABI/cadence.
        java.util.Set<net.metalmod.lighting.PointLight> changed=new java.util.HashSet<>(previousLights);
        changed.addAll(currentLights);
        lightRegions=0;
        for(var light:changed)if(!previousLights.contains(light)||!currentLights.contains(light)) {
            add(null,light.x(),light.y()-light.radius(),light.z(),light.radius(),light.radius()*2+0.5);
            lightRegions++;
        }
        lastProjection.set(projection);lastView.set(camera.viewRotationMatrix);
        return reset;
    }
    private static float rotationDistance(Matrix4fc a, Matrix4fc b) {
        return Math.abs(a.m00()-b.m00())+Math.abs(a.m01()-b.m01())+Math.abs(a.m02()-b.m02())
                +Math.abs(a.m10()-b.m10())+Math.abs(a.m11()-b.m11())+Math.abs(a.m12()-b.m12())
                +Math.abs(a.m20()-b.m20())+Math.abs(a.m21()-b.m21())+Math.abs(a.m22()-b.m22());
    }
    private void add(Object id,double x,double y,double z,double radius,double height) {
        if(count==CAPACITY) { reset=true;reason="object capacity exceeded";return; }
        Position old=id==null?null:previous.get(id);
        if(id!=null)current.put(id,new Position(x,y,z));
        int offset=count++*12;
        objects[offset]=(float)(x-radius-origin.x);objects[offset+1]=(float)(y-0.25-origin.y);
        objects[offset+2]=(float)(z-radius-origin.z);objects[offset+3]=old!=null?1:0;
        objects[offset+4]=(float)(x+radius-origin.x);objects[offset+5]=(float)(y+height-origin.y);
        objects[offset+6]=(float)(z+radius-origin.z);objects[offset+7]=0;
        objects[offset+8]=old==null?0:(float)(old.x-x);objects[offset+9]=old==null?0:(float)(old.y-y);
        objects[offset+10]=old==null?0:(float)(old.z-z);objects[offset+11]=0;
    }
    /** Actual submitted piston/moving-block pose, not a simulation-tick position. */
    public static void movingBlock(Matrix4fc pose, net.minecraft.client.renderer.block.MovingBlockRenderState state) {
        for(var motion:active) {
        if(!motion.trackObjects)continue;
        net.metalmod.Diagnostics.hook("Temporal.movingBlock");
        Vector3f center=pose.transformPosition(0.5f,0,0.5f,new Vector3f());
        motion.add(new MovingId((state.randomSeedPos!=null?state.randomSeedPos:state.blockPos).asLong(),state.blockState),
                center.x+motion.origin.x,center.y+motion.origin.y,center.z+motion.origin.z,1,1.5);
        }
    }
    public MemorySegment matrices(Arena arena) {
        float[] values=new float[48];reprojection.get(values,0);inverse.get(values,16);
        (reset?currentPV:previousPV).get(values,32);
        return arena.allocateFrom(ValueLayout.JAVA_FLOAT,values);
    }
    public MemorySegment objects(Arena arena) {
        var memory=arena.allocate(Math.max(4,count*48L),16);
        for(int i=0;i<count*12;i++)memory.setAtIndex(ValueLayout.JAVA_FLOAT,i,objects[i]);
        return memory;
    }
    public boolean validInputs() {
        if(!inverse.isFinite()||!currentPV.isFinite()||!reprojection.isFinite())return false;
        for(int i=0;i<count*12;i++)if(!Float.isFinite(objects[i]))return false;
        return true;
    }
    public int count(){return count;}
    public boolean reset(){return reset;}
    /** Advance only after the complete temporal command buffer was committed successfully. */
    public void committed() {
        previousLights=currentLights;
        previousPV.set(currentPV);previousOrigin=origin;lastSerial=serial;valid=true;
        Map<Object,Position> swap=previous;previous=current;current=swap;current.clear();frames++;
        stats=new Stats(resets,reason,count,frames,lightRegions);active.remove(this);
    }
}
