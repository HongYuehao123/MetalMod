package net.metalmod.benchmark.mixin;
import java.lang.foreign.MemorySegment;
import net.metalmod.benchmark.DisplayProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="net.metalmod.backend.MetalSurfaceBackend",remap=false)
public class DisplayProbeMixin {
    @Shadow private MemorySegment drawable;
    @Inject(method="present",at=@At("HEAD"))
    private void benchmark$display(CallbackInfo ci) {if(drawable!=null&&drawable.address()!=0)DisplayProbe.observe(drawable);}
}
