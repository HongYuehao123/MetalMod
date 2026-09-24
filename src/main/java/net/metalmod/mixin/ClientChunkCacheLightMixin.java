package net.metalmod.mixin;

import net.metalmod.backend.MetalBackend;
import net.metalmod.lighting.BlockLightIndex;
import net.metalmod.lighting.LightingSettings;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops a chunk's cached block emitters when the client unloads it.
 *
 * <p>This is deliberately the <em>only</em> world-event hook the static index needs. An earlier
 * version also marked every section of every loaded chunk as dirty on load, which at render distance
 * 32 is roughly a hundred thousand sections - and the index only ever queries the few hundred inside
 * its search window, so almost all of that bookkeeping was never read. Reading sections on demand
 * removed the need for it entirely and made the "pending" counter mean something.
 *
 * <p>Invalidation is still required: without it a section cached before a chunk unloaded would keep
 * its emitters and light a chunk that is no longer there. The hook is inert unless the Metal backend
 * is drawing and the dynamic-light switch is on, so the default Vulkan path pays nothing.
 */
@Mixin(net.minecraft.client.multiplayer.ClientChunkCache.class)
public class ClientChunkCacheLightMixin {

    @Inject(method = "drop", at = @At("HEAD"))
    private void metalmod$forgetChunk(ChunkPos pos, CallbackInfo ci) {
        if (!LightingSettings.dynamicLights() || !MetalBackend.isEnabled()) {
            return;
        }
        BlockLightIndex.invalidate(pos.x(), pos.z());
    }
}
