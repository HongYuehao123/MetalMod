package net.metalmod.benchmark.mixin;
import java.lang.foreign.MemorySegment;
import net.metalmod.benchmark.DisplayProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.metalmod.backend.MetalSurfaceBackend",remap=false)
public class DisplayProbeMixin {
    @Shadow private MemorySegment drawable;
    @Unique private boolean benchmark$observed;
    @Inject(method="present",at=@At("HEAD"))
    private void benchmark$display(CallbackInfo ci) {
        benchmark$observed=false;
        benchmark$observe();
    }
    @Inject(method="present",at={
        @At(value="INVOKE",target="Lnet/metalmod/backend/MetalNative;layerPresentTexture(Ljava/lang/foreign/MemorySegment;Ljava/lang/foreign/MemorySegment;Ljava/lang/foreign/MemorySegment;)I"),
        @At(value="INVOKE",target="Lnet/metalmod/backend/MetalNative;layerPresentClear(Ljava/lang/foreign/MemorySegment;Ljava/lang/foreign/MemorySegment;FFFF)I")})
    private void benchmark$lateDisplay(CallbackInfo ci) {benchmark$observe();}
    @Unique private void benchmark$observe() {
        if(!benchmark$observed && drawable!=null && drawable.address()!=0) {
            DisplayProbe.observe(drawable);benchmark$observed=true;
        }
    }
}
