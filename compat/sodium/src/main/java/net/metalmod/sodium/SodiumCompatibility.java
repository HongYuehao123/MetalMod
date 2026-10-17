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
        var core = FabricLoader.getInstance().getModContainer("metalmod").orElseThrow();
        var abi = core.getMetadata().getCustomValue("metalmod:sodium_adapter_abi");
        if (abi == null || abi.getType() != net.fabricmc.loader.api.metadata.CustomValue.CvType.NUMBER
                || abi.getAsNumber().intValue() != 3) {
            throw new IllegalStateException("MetalMod Sodium adapter 0.1.3 requires core adapter ABI 3. "
                    + "Install the core MetalMod and adapter JARs from the same build.");
        }
        FabricLoader.getInstance().getModContainer("sodium").ifPresent(sodium -> {
            String version = sodium.getMetadata().getVersion().getFriendlyString();
            if (!SUPPORTED.equals(version)) {
                throw new IllegalStateException(unsupportedMessage(version));
            }
        });
    }
}
