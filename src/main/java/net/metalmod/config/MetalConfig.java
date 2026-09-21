package net.metalmod.config;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Properties;

public class MetalConfig {

    private static final File CONFIG_FILE = new File("config/metalmod.properties");
    public static final MetalConfig INSTANCE = new MetalConfig();

    public enum ScalingMode {
        OFF(0, "Off"),
        SPATIAL(1, "MetalFX Spatial"),
        TEMPORAL(2, "MetalFX Temporal");

        private final int id;
        private final String displayName;

        ScalingMode(int id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public int getId() {
            return id;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public enum QualityPreset {
        NATIVE(1.00f, "Native (100%)"),
        ULTRA_QUALITY(0.77f, "Ultra Quality (77%)"),
        QUALITY(0.67f, "Quality (67%)"),
        BALANCED(0.58f, "Balanced (58%)"),
        PERFORMANCE(0.50f, "Performance (50%)"),
        ULTRA_PERF(0.33f, "Ultra Performance (33%)");

        private final float scale;
        private final String displayName;

        QualityPreset(float scale, String displayName) {
            this.scale = scale;
            this.displayName = displayName;
        }

        public float getScale() {
            return scale;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    public ScalingMode scalingMode = ScalingMode.SPATIAL;
    public QualityPreset preset = QualityPreset.QUALITY;
    public boolean frameGeneration = true;
    public float sharpness = 0.5f;
    public boolean enableHDR = false;
    public boolean enableUIOverlay = true;
    public int targetDisplayFPS = 120; // ProMotion 120Hz default

    public void load() {
        if (!CONFIG_FILE.exists()) {
            save();
            return;
        }
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            Properties props = new Properties();
            props.load(reader);

            String modeStr = props.getProperty("scalingMode", "SPATIAL");
            try {
                this.scalingMode = ScalingMode.valueOf(modeStr);
            } catch (Exception ignored) {}

            String presetStr = props.getProperty("preset", "QUALITY");
            try {
                this.preset = QualityPreset.valueOf(presetStr);
            } catch (Exception ignored) {}

            this.frameGeneration = Boolean.parseBoolean(props.getProperty("frameGeneration", "true"));
            this.sharpness = Float.parseFloat(props.getProperty("sharpness", "0.5"));
            this.enableHDR = Boolean.parseBoolean(props.getProperty("enableHDR", "false"));
            this.enableUIOverlay = Boolean.parseBoolean(props.getProperty("enableUIOverlay", "true"));
            this.targetDisplayFPS = Integer.parseInt(props.getProperty("targetDisplayFPS", "120"));
        } catch (Exception e) {
            System.err.println("[MetalMod] Failed to load config: " + e.getMessage());
        }
    }

    public void save() {
        try {
            File parent = CONFIG_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                Properties props = new Properties();
                props.setProperty("scalingMode", this.scalingMode.name());
                props.setProperty("preset", this.preset.name());
                props.setProperty("frameGeneration", Boolean.toString(this.frameGeneration));
                props.setProperty("sharpness", Float.toString(this.sharpness));
                props.setProperty("enableHDR", Boolean.toString(this.enableHDR));
                props.setProperty("enableUIOverlay", Boolean.toString(this.enableUIOverlay));
                props.setProperty("targetDisplayFPS", Integer.toString(this.targetDisplayFPS));
                props.store(writer, "MetalMod Apple Silicon Configuration");
            }
        } catch (Exception e) {
            System.err.println("[MetalMod] Failed to save config: " + e.getMessage());
        }
    }
}
