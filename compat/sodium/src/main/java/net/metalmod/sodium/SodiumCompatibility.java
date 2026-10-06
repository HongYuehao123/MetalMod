package net.metalmod.sodium;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/** A fatal version gate belongs in Fabric pre-launch: Mixin can swallow plugin onLoad errors. */
public final class SodiumCompatibility implements PreLaunchEntrypoint {
    public static final String SUPPORTED = "0.9.2+mc26.2";

    public static String unsupportedMessage(String version) {
        return "MetalMod Sodium adapter supports Sodium " + SUPPORTED + "; installed " + version
                + ". Install the supported release or remove Sodium to use vanilla rendering.";
    }

    @Override public void onPreLaunch() {
        FabricLoader.getInstance().getModContainer("sodium").ifPresent(sodium -> {
            String version = sodium.getMetadata().getVersion().getFriendlyString();
            if (!SUPPORTED.equals(version)) {
                throw new IllegalStateException(unsupportedMessage(version));
            }
        });
    }
}
