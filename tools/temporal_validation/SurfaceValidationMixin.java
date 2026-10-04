package net.metalmod.validation.mixin;

import net.metalmod.backend.MetalSurfaceBackend;
import net.metalmod.upscaling.FrameGenerationCoordinator;
import net.metalmod.validation.FrameGenerationValidation;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only sampling at the actual completed render boundary, before next world overwrites history. */
@Mixin(MetalSurfaceBackend.class)
public class SurfaceValidationMixin {
    @Inject(method="present",at=@At("HEAD"),require=1)
    private void validation$owner(CallbackInfo ci) throws ReflectiveOperationException {
        var field=FrameGenerationCoordinator.class.getDeclaredField("pending");field.setAccessible(true);
        FrameGenerationValidation.presentedOwner=(FrameGenerationCoordinator)field.get(null);
    }
    @Inject(method="present",at=@At("RETURN"),require=1)
    private void validation$presented(CallbackInfo ci){FrameGenerationValidation.frame(Minecraft.getInstance());}
}
