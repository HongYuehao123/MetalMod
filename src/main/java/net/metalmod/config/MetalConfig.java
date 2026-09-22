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

    // These fields are written by the config GUI thread and read by the render thread, so they
    // must be volatile for changes to be visible without tearing or stale reads.
    // OFF by default: the RenderTarget scaling hook now actually applies, so a fresh install
    // would otherwise render at a fraction of native resolution and look broken. Set to SPATIAL
    // deliberately when you want to measure GPU headroom (see TESTING.md stage 2).
    public volatile ScalingMode scalingMode = ScalingMode.OFF;
    public volatile QualityPreset preset = QualityPreset.QUALITY;
    public volatile boolean frameGeneration = false; // needs a presentation pacer; see README
    public volatile float sharpness = 0.5f;
    public volatile boolean enableHDR = false;
    public volatile boolean enableUIOverlay = true;
    public volatile int targetDisplayFPS = 120; // ProMotion 120Hz default
    // Gates the UMA *telemetry* shown on F3. The LWJGL allocator interception that used to sit
    // behind this flag was removed: LWJGL 3.4's MemoryAllocator needs native function pointers for
    // its fast path, and mixing libc- and pool-allocated pointers behind one free() risks
    // corruption. See ROADMAP.md, "Memory".
    public volatile boolean enableUnifiedMemoryPool = false;
    public volatile boolean enableMemoryPressureHandler = true; // macOS kernel memory pressure listener
    // Off by default, and deliberately so: the Metal backend is Phase 1 first light. It selects a
    // Metal device and presents cleared frames, but every draw is still a no-op, so the game is
    // unusable while it is on. Enable it only to develop or test the backend; normal play stays on
    // Vulkan/OpenGL. Also settable with -Dmetalmod.metalBackend=true.
    public volatile boolean preferMetalBackend = false;

    public void load() {
        if (!CONFIG_FILE.exists()) {
            save();
            return;
        }
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            Properties props = new Properties();
            props.load(reader);

            String modeStr = props.getProperty("scalingMode", "OFF");
            try {
                this.scalingMode = ScalingMode.valueOf(modeStr);
            } catch (Exception ignored) {}

            String presetStr = props.getProperty("preset", "QUALITY");
            try {
                this.preset = QualityPreset.valueOf(presetStr);
            } catch (Exception ignored) {}

            this.frameGeneration = Boolean.parseBoolean(props.getProperty("frameGeneration", "false"));
            this.sharpness = Float.parseFloat(props.getProperty("sharpness", "0.5"));
            this.enableHDR = Boolean.parseBoolean(props.getProperty("enableHDR", "false"));
            this.enableUIOverlay = Boolean.parseBoolean(props.getProperty("enableUIOverlay", "true"));
            this.targetDisplayFPS = Integer.parseInt(props.getProperty("targetDisplayFPS", "120"));
            this.enableUnifiedMemoryPool = Boolean.parseBoolean(props.getProperty("enableUnifiedMemoryPool", "false"));
            this.enableMemoryPressureHandler = Boolean.parseBoolean(props.getProperty("enableMemoryPressureHandler", "true"));
            this.preferMetalBackend = Boolean.parseBoolean(props.getProperty("preferMetalBackend", "false"));
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
                props.setProperty("enableUnifiedMemoryPool", Boolean.toString(this.enableUnifiedMemoryPool));
                props.setProperty("enableMemoryPressureHandler", Boolean.toString(this.enableMemoryPressureHandler));
                props.setProperty("preferMetalBackend", Boolean.toString(this.preferMetalBackend));
                props.store(writer, "MetalMod Apple Silicon Configuration");
            }
        } catch (Exception e) {
            System.err.println("[MetalMod] Failed to save config: " + e.getMessage());
        }
    }
}
