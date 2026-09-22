package net.metalmod.config;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.Properties;

public class MetalConfig {

    private static final File CONFIG_FILE = new File("config/metalmod.properties");
    public static final MetalConfig INSTANCE = new MetalConfig();

    // These fields are written by the config GUI thread and read by the render thread, so they
    // must be volatile for changes to be visible without tearing or stale reads.

    // Gates the UMA *telemetry* shown on F3. The LWJGL allocator interception that used to sit
    // behind this flag was removed: LWJGL 3.4's MemoryAllocator needs native function pointers for
    // its fast path, and mixing libc- and pool-allocated pointers behind one free() risks
    // corruption. See ROADMAP.md, "Memory".
    public volatile boolean enableUnifiedMemoryPool = false;
    public volatile boolean enableMemoryPressureHandler = true; // macOS kernel memory pressure listener

    // Off by default while visual parity is unfinished; normal play stays on Vulkan/OpenGL. The
    // backend is selected once at startup (PreferredGraphicsApiMixin), so changing this needs a
    // restart. Also settable with -Dmetalmod.metalBackend=true. The config screen exposes it as
    // "Metal Renderer Backend".
    public volatile boolean preferMetalBackend = false;

    // The MetalFX scaling mode, quality preset, frame generation, sharpness, HDR and target-refresh
    // settings that used to live here drove the retired MoltenVK-interop frame pipeline
    // (ROADMAP.md §4). Nothing read them once that pipeline went away, so they were removed rather
    // than left as controls that do nothing.

    public void load() {
        if (!CONFIG_FILE.exists()) {
            save();
            return;
        }
        try (FileReader reader = new FileReader(CONFIG_FILE)) {
            Properties props = new Properties();
            props.load(reader);

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
