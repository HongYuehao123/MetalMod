package net.metalmod.lighting;

import net.metalmod.config.MetalConfig;

/**
 * Precedence and coherence rules for the lighting switches.
 *
 * <p>These are the rules the settings screen relies on, and they are easy to get subtly wrong: a
 * launch flag has to beat a saved setting, and clustering has to be impossible without the set it
 * clusters. Both are checked without a game or a Metal device.
 */
public final class LightingSettingsTest {

    public static int runTests() {
        MetalConfig config = MetalConfig.INSTANCE;
        boolean savedDynamic = config.enableDynamicLights;
        boolean savedClustered = config.enableClusteredLights;
        boolean savedProof = config.enablePointLightProof;
        String savedDynamicProperty = System.getProperty(LightingSettings.PROPERTY_DYNAMIC_LIGHTS);
        String savedClusteredProperty = System.getProperty(LightingSettings.PROPERTY_CLUSTERED_LIGHTS);
        String savedProofProperty = System.getProperty(LightingSettings.PROPERTY_POINT_LIGHT_PROOF);
        try {
            configOffComesFromTheFile();
            clusteredNeedsDynamic();
            launchFlagsWin();
            gameChoiceBeatsLaunchFlag();
            summaryIsReadable();
            onlyOnePlaceResolvesTheSwitches();
            System.out.println("PASS lighting settings: launch-flag precedence and cluster coherence");
            return 0;
        } catch (AssertionError error) {
            error.printStackTrace();
            return 1;
        } finally {
            LightingSettings.clearSessionChoices();
            config.enableDynamicLights = savedDynamic;
            config.enableClusteredLights = savedClustered;
            config.enablePointLightProof = savedProof;
            restore(LightingSettings.PROPERTY_DYNAMIC_LIGHTS, savedDynamicProperty);
            restore(LightingSettings.PROPERTY_CLUSTERED_LIGHTS, savedClusteredProperty);
            restore(LightingSettings.PROPERTY_POINT_LIGHT_PROOF, savedProofProperty);
        }
    }

    /** With no launch flag, the saved file is what decides. */
    private static void configOffComesFromTheFile() {
        clear();
        require(!LightingSettings.dynamicLights() && !LightingSettings.clusteredLights()
                && !LightingSettings.pointLightProof(), "everything defaults off");
        require(!LightingSettings.overridden(LightingSettings.PROPERTY_DYNAMIC_LIGHTS),
                "nothing is overridden without a launch flag");

        MetalConfig.INSTANCE.enableDynamicLights = true;
        MetalConfig.INSTANCE.enablePointLightProof = true;
        require(LightingSettings.dynamicLights(), "the file can switch dynamic lighting on");
        require(LightingSettings.pointLightProof(), "the file can switch the proof on");
        require(!LightingSettings.clusteredLights(), "clustering is still off");
    }

    /** Clustering refines the dynamic set, so it cannot be on while the set is off. */
    private static void clusteredNeedsDynamic() {
        clear();
        MetalConfig.INSTANCE.enableClusteredLights = true;
        require(!LightingSettings.clusteredLights(),
                "clustering is inert with dynamic lighting off");

        MetalConfig.INSTANCE.enableDynamicLights = true;
        require(LightingSettings.clusteredLights(),
                "clustering follows once dynamic lighting is on");

        // Even an explicit launch flag cannot create the inconsistent state.
        System.setProperty(LightingSettings.PROPERTY_CLUSTERED_LIGHTS, "true");
        MetalConfig.INSTANCE.enableDynamicLights = false;
        require(!LightingSettings.clusteredLights(),
                "a launch flag for clustering does not enable it without dynamic lighting");
    }

    /** A launch flag is an explicit per-run instruction, so it wins over the saved value both ways. */
    private static void launchFlagsWin() {
        clear();
        MetalConfig.INSTANCE.enableDynamicLights = false;
        System.setProperty(LightingSettings.PROPERTY_DYNAMIC_LIGHTS, "true");
        require(LightingSettings.dynamicLights(), "a launch flag turns it on over the file");
        require(LightingSettings.overridden(LightingSettings.PROPERTY_DYNAMIC_LIGHTS),
                "and reports itself as overridden, so the screen can lock the row");

        MetalConfig.INSTANCE.enableDynamicLights = true;
        System.setProperty(LightingSettings.PROPERTY_DYNAMIC_LIGHTS, "false");
        require(!LightingSettings.dynamicLights(), "a launch flag turns it off over the file");

        // The offline tools depend on this: they set the flag, create a device, and clear it again.
        System.clearProperty(LightingSettings.PROPERTY_DYNAMIC_LIGHTS);
        require(LightingSettings.dynamicLights(), "clearing the flag restores the file's value");
    }

    /**
     * An in-game choice must beat a launch flag, not just the saved file.
     *
     * <p>This is the bug that made the toggle look broken: the flag was resolved on every read, so
     * launching with {@code -Dmetalmod.dynamicLights=true} pinned the value and the screen could not
     * change it no matter what it wrote.
     */
    private static void gameChoiceBeatsLaunchFlag() {
        clear();
        System.setProperty(LightingSettings.PROPERTY_DYNAMIC_LIGHTS, "true");
        require(LightingSettings.dynamicLights(), "the flag seeds the session on");

        LightingSettings.chooseDynamicLights(false);
        require(!LightingSettings.dynamicLights(), "an in-game choice overrides the launch flag");
        require(!MetalConfig.INSTANCE.enableDynamicLights,
                "and is persisted, so the next launch without the flag is off");

        // The flag is still present, but it must not re-take control.
        require(LightingSettings.overridden(LightingSettings.PROPERTY_DYNAMIC_LIGHTS),
                "the flag is still reported as present");
        require(!LightingSettings.dynamicLights(), "the choice still wins with the flag present");

        LightingSettings.chooseDynamicLights(true);
        require(LightingSettings.dynamicLights(), "and can be turned back on");

        // Turning the set off drops clustering with it; turning it on does not silently enable it.
        LightingSettings.chooseClusteredLights(true);
        require(LightingSettings.clusteredLights(), "clustering can be chosen on");
        LightingSettings.chooseDynamicLights(false);
        require(!LightingSettings.clusteredLights() && !MetalConfig.INSTANCE.enableClusteredLights,
                "turning the set off clears clustering in the session and in the file");

        // Clearing the session choices hands control back to the flag.
        LightingSettings.clearSessionChoices();
        require(LightingSettings.dynamicLights(), "clearing the choice returns to the flag");
    }

    private static void summaryIsReadable() {
        clear();
        String summary = LightingSettings.summary();
        require(summary.contains("dynamicLights=") && summary.contains("clusteredLights=")
                && summary.contains("pointLightProof="), "the summary names every switch: " + summary);
    }

    /**
     * Only {@link LightingSettings} may name a lighting property.
     *
     * <p>A source check, not a behavioural one, because the bug it guards against is precisely
     * "something else read the property directly". The extraction mixin did exactly that, and the
     * symptom was a toggle that half-worked: with a launch flag the light set was collected and the
     * setting responded, and without one the shader variant was built but there was nothing to light
     * with. No test of {@code LightingSettings} can see that; the source can.
     *
     * <p>Skips itself when the source tree is not next to the working directory, so it stays a
     * developer guard rather than a runtime dependency.
     */
    private static void onlyOnePlaceResolvesTheSwitches() {
        java.nio.file.Path root = java.nio.file.Paths.get("src/main/java/net/metalmod");
        if (!java.nio.file.Files.isDirectory(root)) {
            System.out.println("  (source tree not present; skipping the single-resolver check)");
            return;
        }
        String[] literals = {"metalmod.dynamicLights", "metalmod.clusteredLights",
                "metalmod.pointLightProof"};
        java.util.List<String> offenders = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("LightingSettings.java"))
                    .forEach(path -> {
                        String text;
                        try {
                            text = java.nio.file.Files.readString(path);
                        } catch (Exception unreadable) {
                            return;
                        }
                        for (String literal : literals) {
                            if (text.contains("\"" + literal + "\"")) {
                                offenders.add(path + " names " + literal);
                            }
                        }
                    });
        } catch (Exception failure) {
            System.out.println("  (could not scan the source tree: " + failure + ")");
            return;
        }
        require(offenders.isEmpty(),
                "only LightingSettings may name a lighting property, but: " + offenders);
    }

    private static void clear() {
        LightingSettings.clearSessionChoices();
        System.clearProperty(LightingSettings.PROPERTY_DYNAMIC_LIGHTS);
        System.clearProperty(LightingSettings.PROPERTY_CLUSTERED_LIGHTS);
        System.clearProperty(LightingSettings.PROPERTY_POINT_LIGHT_PROOF);
        MetalConfig.INSTANCE.enableDynamicLights = false;
        MetalConfig.INSTANCE.enableClusteredLights = false;
        MetalConfig.INSTANCE.enablePointLightProof = false;
    }

    private static void restore(String property, String value) {
        if (value == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, value);
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
