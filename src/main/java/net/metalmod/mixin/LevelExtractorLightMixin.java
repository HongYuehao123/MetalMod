package net.metalmod.mixin;

import net.metalmod.lighting.BlockLightIndex;
import net.metalmod.lighting.LightingSettings;
import net.metalmod.lighting.LightCollector;
import net.metalmod.Diagnostics;
import net.metalmod.backend.MetalBackend;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The actual 26.2 extraction boundary; completed before GameRenderer renders the frame. */
@Mixin(LevelExtractor.class)
public class LevelExtractorLightMixin {
    @Inject(method = "extract", at = @At("RETURN"))
    private void metalmod$collectLights(DeltaTracker delta, Camera camera, float partialTick,
                                        CallbackInfo ci) {
        Diagnostics.hook("LevelExtractor.extract");
        // LightingSettings, never the raw property: the switch can be turned on from the settings
        // screen, and a property read here would mean the light set was never collected unless the
        // game had also been launched with -Dmetalmod.dynamicLights. That made the toggle look
        // half-working: the shader variant appeared, but there was nothing to light with.
        if (LightingSettings.dynamicLights() && MetalBackend.isEnabled()) {
            LightCollector.extract(Minecraft.getInstance().level, camera, partialTick);
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void metalmod$clearLights(net.minecraft.client.multiplayer.ClientLevel level, CallbackInfo ci) {
        Diagnostics.hook("LevelExtractor.setLevel");
        LightCollector.clear();
        // A different level, or no level: every indexed section belongs to the world being left.
        BlockLightIndex.clear();
    }
}
