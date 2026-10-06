package net.metalmod.mixin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.client.server.IntegratedServer;
import net.metalmod.performance.ThreadQos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** runServer is declared on MinecraftServer; apply only on the integrated server's own thread. */
@Mixin(MinecraftServer.class)
public class IntegratedServerQosMixin {
    @Inject(method = "runServer", at = @At("HEAD"))
    private void metalmod$serverQos(CallbackInfo ci) {
        if ((Object) this instanceof IntegratedServer) ThreadQos.serverThread();
    }
}
