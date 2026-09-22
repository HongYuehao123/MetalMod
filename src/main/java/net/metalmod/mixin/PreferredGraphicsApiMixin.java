package net.metalmod.mixin;

import com.mojang.blaze3d.systems.GpuBackend;
import net.metalmod.backend.MetalBackend;
import net.minecraft.client.PreferredGraphicsApi;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

/**
 * Puts Metal first in the backend order Minecraft tries.
 *
 * <p>The vanilla method returns a fixed array; this prepends Metal while keeping the vanilla
 * entries as the fallback, so a Metal failure degrades to Vulkan/OpenGL exactly as before.
 */
@Mixin(PreferredGraphicsApi.class)
public abstract class PreferredGraphicsApiMixin {

    @Inject(method = "getBackendsToTry", at = @At("RETURN"), cancellable = true)
    private void metalmod$preferMetal(CallbackInfoReturnable<GpuBackend[]> cir) {
        GpuBackend[] vanilla = cir.getReturnValue();
        if (vanilla == null || Arrays.stream(vanilla).anyMatch(b -> b instanceof MetalBackend)) {
            return;
        }
        MetalBackend metal = MetalBackend.tryCreate();
        if (metal == null) {
            return;
        }
        GpuBackend[] result = new GpuBackend[vanilla.length + 1];
        result[0] = metal;
        System.arraycopy(vanilla, 0, result, 1, vanilla.length);
        cir.setReturnValue(result);
    }
}
