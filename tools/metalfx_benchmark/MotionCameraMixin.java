package net.metalmod.benchmark.mixin;
import net.metalmod.benchmark.MotionBenchmark;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(GameRenderer.class)
public class MotionCameraMixin {
    @Inject(method="extract",at=@At("HEAD"))
    private void benchmark$pose(DeltaTracker tracker,boolean active,CallbackInfo ci) {MotionBenchmark.beforeExtract();}
    @Inject(method="render",at=@At("RETURN"))
    private void benchmark$image(DeltaTracker tracker,boolean active,CallbackInfo ci) {MotionBenchmark.afterRender();}
}
