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
            "Window.onFramebufferResize",
            // Phase 6: the light set is extracted at the engine's own extraction boundary, and the
            // set is dropped whenever the extracted level changes.
            "LevelExtractor.extract",
            "LevelExtractor.setLevel",
            // The in-game entry points: the vanilla Options screen's MetalMod button, and the
            // Lighting page. Reported so a missing button can be told from a missing injection.
            "OptionsScreen.init",
            "MetalModLightingConfigScreen.init"
    );

    /**
     * Of {@link #HOOKS}, the ones whose code runs on any session that draws a frame.
     *
     * <p>Absence here is a fault, and it is what {@link #missing()} reports. The two screen hooks are
     * deliberately not members: they run when their screen is opened, so a session that never opens the
     * settings would show a red "hooks missing OptionsScreen.init MetalModLightingConfigScreen.init" -
     * an alarm reading "you have not opened a menu yet". Permanent, unactionable, and exactly the kind
     * of line that trains the reader to ignore the one that matters. Their proof stays where it is
     * unambiguous: the {@code HOOK ACTIVE} line in the log, printed the first time the button or the
     * Lighting page runs.
     */
    private static final List<String> EXPECTED_EVERY_SESSION = List.of(
            "GameRenderer.render",
            "GameRenderer.resize",
            "Window.onFramebufferResize",
            "LevelExtractor.extract",
            "LevelExtractor.setLevel");

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

    /**
     * The hooks that should have reported by now and have not; empty when every one applied.
     *
     * <p>Only {@link #EXPECTED_EVERY_SESSION} is consulted. A hook that waits for a screen to open is
     * not a fault until that screen has been opened, and this method cannot tell the two apart, so it
     * does not guess. Use {@link #summary()} for the full checklist.
     */
    public static String missing() {
        StringBuilder sb = new StringBuilder();
        for (String hook : EXPECTED_EVERY_SESSION) {
            if (!SEEN.contains(hook)) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(hook);
            }
        }
        return sb.toString();
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

        // The lighting switches are startup-only, so a log that does not name them cannot say which
        // path a session actually ran. Printed every launch, on or off, for that reason.
        boolean dynamic = net.metalmod.lighting.LightingSettings.dynamicLights();
        System.out.println(TAG + " lighting: " + net.metalmod.lighting.LightingSettings.summary()
                + " (from " + (net.metalmod.lighting.LightingSettings.overridden(
                        net.metalmod.lighting.LightingSettings.PROPERTY_DYNAMIC_LIGHTS)
                        || net.metalmod.lighting.LightingSettings.overridden(
                                net.metalmod.lighting.LightingSettings.PROPERTY_CLUSTERED_LIGHTS)
                        ? "launch flags and config" : "config/metalmod.properties") + ")");
        if (!dynamic) {
            System.out.println(TAG + " expect  : no dynamic lighting this session. Enable it in "
                    + "Mod Menu -> MetalMod -> Lighting, or with -Dmetalmod.dynamicLights=true.");
        }

        System.out.println(TAG + " expect  : the native Metal backend draws only when the engine "
                + "selects it (preferMetalBackend or -Dmetalmod.metalBackend=true).");
        System.out.println(TAG + " expect  : mixin hooks report themselves below as 'HOOK ACTIVE'. "
                + "A hook that never appears did not apply.");
        System.out.println(TAG + " ========================================================");
    }
}
