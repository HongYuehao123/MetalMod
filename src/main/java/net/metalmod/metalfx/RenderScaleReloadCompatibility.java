package net.metalmod.metalfx;

/** Isolates BUG-029's resource-reload workaround until controlled sky captures explain the stale state.
 * Pipeline invalidation is kept separately; neither operation is removed on an unverified theory.
 */
final class RenderScaleReloadCompatibility {
    private static volatile boolean reloadRequested;
    private RenderScaleReloadCompatibility() { }
    static boolean pending() { return reloadRequested; }
    static void request() {
        if (reloadRequested) {
            return;
        }
        try {
            net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
            if (minecraft == null) {
                return;
            }
            reloadRequested = true;
            minecraft.delayTextureReload().whenComplete((unused, error) -> {
                reloadRequested = false;
                if (error != null) {
                    System.err.println("[MetalMod] resource reload after the render-scale change failed: "
                            + error);
                } else {
                    System.out.println("[MetalMod] resource reload completed after the render-scale"
                            + " change");
                }
            });
            System.out.println("[MetalMod] render scale changed: reloading resources so nothing cached"
                    + " against the old target survives");
        } catch (Throwable t) {
            reloadRequested = false;
            System.err.println("[MetalMod] could not request a resource reload: " + t);
        }
    }

}
