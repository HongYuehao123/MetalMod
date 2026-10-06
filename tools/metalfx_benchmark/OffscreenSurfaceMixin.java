package net.metalmod.benchmark.mixin;

import java.lang.foreign.MemorySegment;
import net.metalmod.backend.*;
import net.metalmod.benchmark.OffscreenCompletion;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Not packaged in the production mod. Keeps normal frame resource lifecycles intact. */
@Mixin(targets="net.metalmod.backend.MetalSurfaceBackend",remap=false)
public class OffscreenSurfaceMixin {
    @Shadow @Final private MetalDevice device;
    @Shadow private boolean configured;
    @Shadow private boolean closed;
    @Shadow private boolean frameAcquired;
    @Shadow private MemorySegment drawable;
    @Shadow private MemorySegment sourceTexture;
    @Shadow private long renderedFrameId;
    @Inject(method="acquireNextTexture",at=@At("HEAD"),cancellable=true)
    private void benchmark$acquire(CallbackInfo ci) {
        if(!OffscreenCompletion.ENABLED) return;
        OffscreenCompletion.beforeFrame();
        if(!OffscreenCompletion.offscreen) return;
        if(!configured || closed || frameAcquired) throw new IllegalStateException("Invalid offscreen frame lifecycle");
        MetalNative.utilityEndFrame();drawable=MemorySegment.NULL;frameAcquired=true;ci.cancel();
    }
    @Inject(method="present",at=@At("HEAD"),cancellable=true)
    private void benchmark$present(CallbackInfo ci) {
        if(!OffscreenCompletion.ENABLED || !OffscreenCompletion.offscreen) return;
        if(!frameAcquired) throw new IllegalStateException("Offscreen frame not acquired");
        device.applyPendingLightingSettings();device.endDynamicLightFrame();MetalDevice.endFrame();
        frameAcquired=false;renderedFrameId++;sourceTexture=MemorySegment.NULL;
        OffscreenCompletion.frame(device);ci.cancel();
    }
    @Inject(method="present",at=@At("RETURN"))
    private void benchmark$completed(CallbackInfo ci) {
        if(OffscreenCompletion.ENABLED && !OffscreenCompletion.offscreen) OffscreenCompletion.frame(device);
    }
}
