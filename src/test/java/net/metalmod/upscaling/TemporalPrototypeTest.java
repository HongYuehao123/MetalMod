package net.metalmod.upscaling;

import java.lang.foreign.MemorySegment;
import net.metalmod.backend.MetalNative;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.io.IOException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Checks sampling signs and the optional Panama temporal ABI against the real dylib. */
public final class TemporalPrototypeTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static int runTests() {
        MemorySegment device = MemorySegment.NULL, scaler = MemorySegment.NULL, color = MemorySegment.NULL;
        try {
            var first = TemporalSampling.jitter(0);
            require(first.x() == 0 && Math.abs(first.y() + 1.0f / 6) < 1e-6, "first jitter sample");
            require(first.equals(TemporalSampling.jitter(16)), "deterministic jitter wrap/reset");
            for (int i = 0; i < 16; i++) {
                var sample = TemporalSampling.jitter(i);
                require(Math.abs(sample.x()) <= 0.5 && Math.abs(sample.y()) <= 0.5, "jitter pixel bounds");
            }
            // In NDC, +X is right and -Y is down: previous lookup must be up/left.
            var moved = TemporalSampling.motion(0.2f, -0.2f, 0, 0, 100, 100);
            require(moved.x() == -10 && moved.y() == -10, "right/down object: negative pixel motion");
            var still = TemporalSampling.motion(0.3f, 0.4f, 0.3f, 0.4f, 100, 80);
            require(still.x() == 0 && still.y() == 0, "stationary unjittered geometry");
            boolean rejected = false;
            try { TemporalSampling.motion(Float.NaN, 0, 0, 0, 100, 100); }
            catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "invalid geometry rejected");
            var offset = new TemporalSampling.Offset(0.25f, -0.125f);
            for (Matrix4f projection : new Matrix4f[]{new Matrix4f(),
                    new Matrix4f().perspective(1.1f, 1.5f, 100, 0.05f, true).rotateZ(0.1f)}) {
                Matrix4f saved = new Matrix4f(projection);
                Matrix4f shifted = TemporalSampling.jitterProjection(projection, offset, 120, 80);
                for (float z : new float[]{-1, -20}) {
                    Vector4f a = projection.transform(new Vector4f(0.1f, 0.2f, z, 1));
                    Vector4f b = shifted.transform(new Vector4f(0.1f, 0.2f, z, 1));
                    require(Math.abs((b.x/b.w-a.x/a.w)*60 + 0.25f)<1e-5, "pixel jitter X sign/depth independence");
                    require(Math.abs(-(b.y/b.w-a.y/a.w)*40 - 0.125f)<1e-5, "pixel jitter Y sign/depth independence");
                    require(a.z == b.z && a.w == b.w, "depth/clip W unchanged");
                }
                require(projection.equals(saved), "source projection not mutated");
            }
            var proof = new TemporalJitterProof();
            Matrix4f extracted = new Matrix4f();
            proof.begin(false, 1, 120, 80);
            require(proof.projection(extracted, false) == extracted, "ordinary projection path returns original");
            proof.begin(true, 1, 120, 80);
            Matrix4f world = proof.projection(extracted, false), hand = proof.projection(extracted, true);
            require(hand==extracted && !world.equals(hand) && extracted.equals(new Matrix4f()), "world samples jitter; native hand stays unjittered without camera mutation");
            var firstProof = TemporalJitterProof.stats();
            require(firstProof.worldUploads()==1 && firstProof.handUploads()==0, "proof upload diagnostics");
            proof.end();
            require(proof.projection(extracted, false)==extracted, "GUI boundary bypasses jitter");
            proof.begin(true, 1, 120, 80);
            require(TemporalJitterProof.stats().frames()==2, "steady generation advances jitter");
            proof.begin(true, 2, 120, 80);
            require(TemporalJitterProof.stats().frames()==1 && TemporalJitterProof.stats().jitterY()==firstProof.jitterY(),
                    "generation transition restarts jitter");
            proof.reset();
            verifyHookTargets();
            require(MetalNative.isAvailable(), "native library loaded");
            require(!MetalNative.temporalSupported(MemorySegment.NULL), "null capability denied");
            require(!MetalNative.temporalHealthy(MemorySegment.NULL), "null health denied");
            require(MetalNative.temporalEncode(MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                    MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL,
                    0.25f, -0.25f, false, true) == -1, "encode ABI floats/bools/null rejection");
            device = MetalNative.deviceCreate();
            require(device.address() != 0, "host Metal device");
            color = MetalNative.temporalColorCreate(device, 70);
            require(color.address()!=0 && MetalNative.temporalColorHealthy(color), "real colour conversion FFI creation");
            require(MetalNative.temporalColorEncode(color,MemorySegment.NULL,MemorySegment.NULL,MemorySegment.NULL,true)==-1,
                    "colour conversion FFI rejection");
            if (MetalNative.temporalSupported(device)) {
                scaler = MetalNative.temporalCreate(device, 48, 36, 64, 48);
                require(scaler.address() != 0 && MetalNative.temporalHealthy(scaler), "real temporal FFI creation");
                for (int role=0; role<5; role++)
                    require(MetalNative.temporalTextureUsage(scaler, role) > 0, "required usage query " + role);
                require(MetalNative.temporalTextureUsage(scaler, 5) == -1, "invalid usage role denied");
                require(MetalNative.temporalCreate(device, 0, 32, 64, 48).address() == 0, "invalid dimensions denied");
            } else System.out.println("SKIP temporal FFI creation: device unsupported");
            System.out.println("PASS temporal prototype sampling signs and Panama ABI");
            return 0;
        } catch (AssertionError | RuntimeException | IOException e) { e.printStackTrace(); return 1; }
        finally {
            MetalNative.temporalRelease(scaler);
            MetalNative.temporalColorRelease(color);
            if (device.address() != 0) MetalNative.deviceRelease(device);
        }
    }

    /** Inspect the real client bytecode, not a historical method name or a stub signature. */
    private static void verifyHookTargets() throws IOException {
        ClassNode renderer = new ClassNode();
        try (var source=TemporalPrototypeTest.class.getClassLoader()
                .getResourceAsStream("net/minecraft/client/renderer/GameRenderer.class")) {
            require(source!=null,"real GameRenderer bytecode available");
            new ClassReader(source).accept(renderer,ClassReader.SKIP_DEBUG|ClassReader.SKIP_FRAMES);
        }
        int world=0,hand=0,depth=0,worldAt=-1,handAt=-1,depthAt=-1,drawHandAt=-1;
        for (var method:renderer.methods) if (method.name.equals("renderLevel")
                && method.desc.equals("(Lnet/minecraft/client/DeltaTracker;)V")) {
            int at=0;
            for (var instruction:method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    if (call.owner.equals("net/minecraft/client/renderer/ProjectionMatrixBuffer") && call.name.equals("getBuffer")) {
                        if (call.desc.equals("(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;")) { world++;worldAt=at; }
                        if (call.desc.equals("(Lnet/minecraft/client/renderer/Projection;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;")) { hand++;handAt=at; }
                    }
                    if (call.owner.equals("com/mojang/blaze3d/systems/CommandEncoder") && call.name.equals("clearDepthTexture")
                            && call.desc.equals("(Lcom/mojang/blaze3d/textures/GpuTexture;D)V")) { depth++;depthAt=at; }
                    if (call.owner.equals("net/minecraft/client/renderer/GameRenderer") && call.name.equals("renderItemInHand")) drawHandAt=at;
                }
                at++;
            }
        }
        require(world==1 && hand==1 && depth==1,"unique real projection/depth redirect targets");
        require(worldAt<handAt && handAt<depthAt && depthAt<drawHandAt,"world projection then hand projection/clear/draw ordering");
        ClassNode mixin=new ClassNode();
        try (var source=TemporalPrototypeTest.class.getClassLoader()
                .getResourceAsStream("net/metalmod/mixin/SuperResolutionMixin.class")) {
            require(source!=null,"compiled mixin available");
            new ClassReader(source).accept(mixin,ClassReader.SKIP_CODE);
        }
        int runtimeRedirects=0;
        for (var method:mixin.methods) if (method.name.equals("metalmod$worldProjection")
                || method.name.equals("metalmod$handProjection") || method.name.equals("metalmod$preserveWorldDepth")) {
            if (method.visibleAnnotations!=null) for (var annotation:method.visibleAnnotations)
                if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Redirect;")) runtimeRedirects++;
        }
        require(runtimeRedirects==3,"projection/depth Redirect annotations retain runtime visibility");
        System.out.println("PASS real 26.2 projection/depth hook targets, ordering and runtime annotation retention");
    }
}
