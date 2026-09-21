package net.metalmod.mixin;

import net.metalmod.render.VulkanFrameManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = {
    "net.minecraft.client.Minecraft",
    "net.minecraft.class_310"
})
public class MinecraftMixin {

    @Inject(
        method = {"resizeDisplay", "method_1590", "method_1523"},
        at = @At("RETURN"),
        require = 0
    )
    private void onResizeDisplay(CallbackInfo ci) {
        VulkanFrameManager.getInstance().markConfigDirty();
    }
}
