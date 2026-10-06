package net.metalmod.sodium;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import java.util.List;
import java.util.Set;

/** Enable only the exact tested release. Native Metal selection remains device-specific. */
public final class AdapterPlugin implements IMixinConfigPlugin {
    private boolean enabled;

    @Override public void onLoad(String mixinPackage) {
        var sodium = FabricLoader.getInstance().getModContainer("sodium");
        if (sodium.isEmpty()) {
            System.clearProperty("metalmod.sodiumAdapter");
            System.out.println("[MetalMod] Sodium absent: vanilla Metal rendering selected");
            return;
        }
        String version = sodium.orElseThrow()
                .getMetadata().getVersion().getFriendlyString();
        if (!SodiumCompatibility.SUPPORTED.equals(version)) {
            // Mixin logs and swallows onLoad exceptions. Register no unsafe targets here and let
            // the Fabric pre-launch entrypoint terminate startup with the actionable message.
            System.clearProperty("metalmod.sodiumAdapter");
            System.err.println("[MetalMod] " + SodiumCompatibility.unsupportedMessage(version));
            return;
        }
        this.enabled = true;
        System.setProperty("metalmod.sodiumAdapter", "true");
        System.out.println("[MetalMod] Optional native Sodium adapter enabled for " + version);
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) { return this.enabled; }
    @Override public void acceptTargets(Set<String> mine, Set<String> others) {}
    // No target names are registered when Sodium is absent. In particular, neither the factories
    // nor their Sodium subclasses are resolved or initialized on the vanilla path.
    @Override public List<String> getMixins() {
        return this.enabled ? List.of("DrawContextMixin", "MultiDrawBatchMixin") : List.of();
    }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
