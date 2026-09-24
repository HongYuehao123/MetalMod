package net.metalmod.lighting;

import net.metalmod.config.MetalConfig;

/**
 * The one place that decides whether each lighting path is on.
 *
 * <p>Three things can decide a switch, and the order between them is the whole point:
 *
 * <ol>
 *   <li>an <b>in-game choice</b> made on the Lighting screen, which wins for the rest of the session;</li>
 *   <li>a <b>{@code -D} launch flag</b>, which seeds the session's starting value;</li>
 *   <li>the <b>saved config file</b>, which is what is left when neither of the others applies.</li>
 * </ol>
 *
 * <p>A launch flag beating the file matters because the offline tools - the shader inventory and the
 * render check - drive the lighting paths that way, and a saved toggle that silently outvoted them
 * would make those runs lie about what they exercised. But a flag must not beat the *user*: resolving
 * the flag on every read meant that launching with {@code -Dmetalmod.dynamicLights=true} froze the
 * value, so the in-game toggle could not change anything. Hence {@link #chooseDynamicLights} and its
 * siblings, which record a session choice and persist it, and hence the flag being consulted only
 * until such a choice exists.
 *
 * <p>Reads are resolved live rather than cached. A lighting variant is chosen when a pipeline is
 * compiled, so the device samples these when it is created and again whenever the user changes a
 * setting; nothing else should read the config fields directly.
 */
public final class LightingSettings {

    public static final String PROPERTY_POINT_LIGHT_PROOF = "metalmod.pointLightProof";
    public static final String PROPERTY_DYNAMIC_LIGHTS = "metalmod.dynamicLights";
    public static final String PROPERTY_CLUSTERED_LIGHTS = "metalmod.clusteredLights";

    /**
     * Choices made on the Lighting screen this session. Null means "not chosen yet", which is what
     * lets a launch flag seed the value until the user disagrees with it.
     */
    private static volatile Boolean sessionPointLightProof;
    private static volatile Boolean sessionDynamicLights;
    private static volatile Boolean sessionClusteredLights;

    /**
     * Set by the extraction hook each frame when the local player is spectating.
     *
     * <p>This is the second half of an AND, and it is deliberately not a setting: spectator mode turns
     * the dynamic set off, and the player's own choice is left exactly as they made it. Nothing in the
     * interface says so - the toggle keeps showing what they chose - because the suppression is a
     * property of the game mode, not a change they asked for and would then have to undo.
     */
    private static volatile boolean suppressed;

    private LightingSettings() {}

    /** Whether the user launched with a flag for this setting. Informational; it does not lock it. */
    public static boolean overridden(String property) {
        return System.getProperty(property) != null;
    }

    /**
     * Whether dynamic lighting is being evaluated at all: the user's switch, and not suppressed.
     *
     * <p>This is what the renderer should ask. {@link #dynamicLights()} remains the setting, and is
     * what the settings screen shows, so suppressing for a game mode cannot silently rewrite a choice
     * the player made.
     */
    public static boolean active() {
        return dynamicLights() && !suppressed;
    }

    /** Turn evaluation off without touching any setting. Set by the extraction hook each frame. */
    public static void setSuppressed(boolean value) {
        suppressed = value;
    }

    /** Whether evaluation is currently suppressed by the game rather than by the player. */
    public static boolean suppressed() {
        return suppressed;
    }

    /** Whether the artificial single-light proof is active. Diagnostic only. */
    public static boolean pointLightProof() {
        return resolve(sessionPointLightProof, PROPERTY_POINT_LIGHT_PROOF,
                MetalConfig.INSTANCE.enablePointLightProof);
    }

    /** Whether the moving-source light set is collected and evaluated. */
    public static boolean dynamicLights() {
        return resolve(sessionDynamicLights, PROPERTY_DYNAMIC_LIGHTS,
                MetalConfig.INSTANCE.enableDynamicLights);
    }

    /**
     * Whether lights are evaluated through the cluster grid instead of the flat list.
     *
     * <p>Clustering is a refinement of the dynamic set, never a separate feature: with dynamic
     * lighting off there is nothing to cluster, so this reports false and the UI cannot create the
     * inconsistent state where one is on and the other is not.
     */
    public static boolean clusteredLights() {
        return dynamicLights()
                && resolve(sessionClusteredLights, PROPERTY_CLUSTERED_LIGHTS,
                        MetalConfig.INSTANCE.enableClusteredLights);
    }

    // --- in-game choices -------------------------------------------------------------------------
    //
    // Each records the session override, persists the value for the next launch, and keeps the two
    // dependent switches coherent. The caller only has to say what the user asked for.

    public static void choosePointLightProof(boolean on) {
        sessionPointLightProof = on;
        MetalConfig.INSTANCE.enablePointLightProof = on;
        MetalConfig.INSTANCE.save();
    }

    public static void chooseDynamicLights(boolean on) {
        sessionDynamicLights = on;
        MetalConfig.INSTANCE.enableDynamicLights = on;
        if (!on) {
            // Clustering a set that is not collected is not a state worth saving.
            sessionClusteredLights = false;
            MetalConfig.INSTANCE.enableClusteredLights = false;
        }
        MetalConfig.INSTANCE.save();
    }

    public static void chooseClusteredLights(boolean on) {
        sessionClusteredLights = on;
        MetalConfig.INSTANCE.enableClusteredLights = on;
        if (on) {
            // Turning clustering on implies the set it clusters.
            sessionDynamicLights = true;
            MetalConfig.INSTANCE.enableDynamicLights = true;
        }
        MetalConfig.INSTANCE.save();
    }

    /** Forget in-game choices, so the launch flags and the saved file decide again. For tests. */
    public static void clearSessionChoices() {
        sessionPointLightProof = null;
        sessionDynamicLights = null;
        sessionClusteredLights = null;
        suppressed = false;
    }

    private static boolean resolve(Boolean sessionChoice, String property, boolean configured) {
        if (sessionChoice != null) {
            return sessionChoice;
        }
        String override = System.getProperty(property);
        return override != null ? Boolean.parseBoolean(override) : configured;
    }

    /** One-line summary for the log and the F3 section. The setting, not the suppressed state. */
    public static String summary() {
        return "pointLightProof=" + pointLightProof() + " dynamicLights=" + dynamicLights()
                + " clusteredLights=" + clusteredLights();
    }
}
