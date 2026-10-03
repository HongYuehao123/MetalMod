package net.metalmod.mixin;

import net.metalmod.upscaling.TemporalJitterProof;
import net.metalmod.upscaling.UpscalingSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Keep unjittered picking/projection; widen only box culling by two scene pixels. */
@Mixin(Frustum.class)
public class TemporalFrustumMixin {
    @ModifyArgs(method="cubeInFrustum(DDDDDD)I",at=@At(value="INVOKE",target=
            "Lorg/joml/FrustumIntersection;intersectAab(FFFFFF)I"),require=1)
    private void metalmod$margin(Args args) {
        var settings=UpscalingSettings.current();
        if(net.metalmod.backend.MetalDevice.active()==null||!TemporalJitterProof.requested()||!settings.enabled()||settings.strength()==0)return;
        var window=Minecraft.getInstance().getWindow();
        var size=UpscalingSettings.dimensions(window.getWidth(),window.getHeight(),settings);
        float distance=0;for(int i=0;i<6;i++)distance=Math.max(distance,Math.abs((float)args.get(i)));
        float margin=Math.max(0.01f,2*distance/Math.max(1,Math.min(size.width(),size.height())));
        for(int i=0;i<6;i++)args.set(i,(float)args.get(i)+(i<3?-margin:margin));
    }
}
