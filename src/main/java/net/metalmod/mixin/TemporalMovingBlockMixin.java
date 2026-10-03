package net.metalmod.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.metalmod.upscaling.TemporalSceneMotion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SubmitNodeCollection.class)
public class TemporalMovingBlockMixin {
    @Inject(method="submitMovingBlock",at=@At("HEAD"),require=1)
    private void metalmod$movingBlock(PoseStack pose, MovingBlockRenderState state,int light,CallbackInfo ci) {
        TemporalSceneMotion.movingBlock(pose.last().pose(),state);
    }
}
