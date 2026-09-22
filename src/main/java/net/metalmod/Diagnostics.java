package net.metalmod;

import net.metalmod.config.MetalConfig;
import net.metalmod.ffi.MetalBridge;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Test instrumentation for answering "did my hook actually run?".
 *
 * Every mixin injection in this mod declares {@code require = 0}, and the mixin config sets
 * {@code defaultRequire: 0}. A signature or method-name mismatch therefore fails <em>silently</em>:
 * the game starts normally and the feature simply never runs. Absence of an error is not evidence
 * that a hook applied.
 *
 * So each hook reports itself here on first execution, and the window title / log carry a status
 * even when no mixin applies at all. Check the log for {@code HOOK ACTIVE} lines.
 */
public final class Diagnostics {

    private static final String TAG = "[MetalMod]";

    /**
     * Hooks in reporting order, so the summary reads as a checklist.
     *
     * These names are the methods actually verified to exist in the Minecraft 26.2 client jar with
     * javap. The previous list named methods that do not exist in this build
     * (RenderTarget.blitToScreen, GameRenderer.getBasicProjectionMatrix, Minecraft.resizeDisplay,
     * Window.onFramebufferSizeChanged).
     *
     * RenderTarget.resize is deliberately no longer hooked: scaling the main render target makes
     * the GUI's scissor rectangles exceed the render area and breaks input handling.
     */
    private static final List<String> HOOKS = List.of(
            "GameRenderer.render",
            "GameRenderer.resize",
            "Window.onFramebufferResize"
    );

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private Diagnostics() {
    }

    /** Record that a hook ran. Logs the first time each hook is seen. */
    public static void hook(String name) {
        if (SEEN.add(name)) {
            System.out.println(TAG + " HOOK ACTIVE: " + name);
            System.out.println(TAG + " hooks so far: " + summary());
        }
    }

    public static boolean hasHook(String name) {
        return SEEN.contains(name);
    }

    /** A +/- checklist of every hook this mod installs. */
    public static String summary() {
        StringBuilder sb = new StringBuilder();
        for (String hook : HOOKS) {
            sb.append(SEEN.contains(hook) ? '+' : '-').append(hook).append(' ');
        }
        return sb.toString().trim();
    }

    /** True once the per-frame hook has fired, i.e. the FrameManager is actually running. */
    public static boolean framePipelineHooksActive() {
        return hasHook("GameRenderer.render");
    }

    /**
     * One-time environment report. Written at startup so a support log contains everything needed
     * to tell "the mod did nothing" apart from "the mod failed".
     */
    public static void reportEnvironment() {
        String osName = System.getProperty("os.name", "?");
        String osVersion = System.getProperty("os.version", "?");
        String osArch = System.getProperty("os.arch", "?");

        // Touch MetalBridge first: its static initialiser logs the library load result, and letting
        // it fire later would interleave that line into the middle of this report.
        boolean nativeAvailable = MetalBridge.isAvailable();
        String nativeError = MetalBridge.getLoadError();
        MetalConfig config = MetalConfig.INSTANCE;

        System.out.println(TAG + " ================= MetalMod diagnostics =================");
        System.out.println(TAG + " os      : " + osName + " " + osVersion + " (" + osArch + ")");
        System.out.println(TAG + " java    : " + System.getProperty("java.version", "?")
                + " / " + System.getProperty("java.vm.name", "?"));
        System.out.println(TAG + " native  : " + (nativeAvailable
                ? "libmetalmod.dylib loaded"
                : "NOT LOADED - " + nativeError));

        if (osArch != null && osArch.contains("x86")) {
            System.out.println(TAG + " WARNING : Java is running as x86_64. The bundled "
                    + "libmetalmod.dylib is arm64-only and will fail to load under Rosetta.");
        }

        System.out.println(TAG + " config  : preferMetalBackend=" + config.preferMetalBackend
                + " umaPool=" + config.enableUnifiedMemoryPool
                + " pressureHandler=" + config.enableMemoryPressureHandler);

        System.out.println(TAG + " expect  : the native Metal backend draws only when the engine "
                + "selects it (preferMetalBackend or -Dmetalmod.metalBackend=true).");
        System.out.println(TAG + " expect  : mixin hooks report themselves below as 'HOOK ACTIVE'. "
                + "A hook that never appears did not apply.");
        System.out.println(TAG + " ========================================================");
    }
}
