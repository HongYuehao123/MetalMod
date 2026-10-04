package net.metalmod.config;

import java.nio.file.Files;
import net.metalmod.upscaling.FrameGenerationSettings;

/** Preference precedence and real config round trips without touching the user's config. */
public final class FrameGenerationConfigTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static int runTests() {
        java.nio.file.Path directory = null;
        try {
            require(!FrameGenerationSettings.resolve(null,null,null,false,0).enabled(), "default Off");
            require(FrameGenerationSettings.resolve(null,null,null,true,0).enabled(), "saved On");
            require(FrameGenerationSettings.resolve(null,null,"true",false,0).enabled(), "legacy flag preserved");
            require(!FrameGenerationSettings.resolve(null,"false","true",true,0).enabled(), "new flag beats legacy/file");
            var on = FrameGenerationSettings.resolve(true,"false","false",false,5);
            var off = FrameGenerationSettings.resolve(false,"true","true",true,6);
            require(on.enabled() && on.revision()==5 && !off.enabled() && off.revision()==6, "live UI beats flags both ways");
            require(on.enabled(), "old frame snapshot remains immutable");
            require(FrameGenerationSettings.resolve(null,"bad","bad",true,0).enabled(), "invalid flags retain saved preference");
            boolean saved = MetalConfig.INSTANCE.enableFrameGeneration;
            String newFlag = System.getProperty("metalmod.frameGeneration");
            String legacyFlag = System.getProperty("metalmod.displayLink");
            try {
                MetalConfig.INSTANCE.enableFrameGeneration = true;
                System.setProperty("metalmod.frameGeneration", "true");
                System.setProperty("metalmod.displayLink", "true");
                require(FrameGenerationSettings.gameplayAvailable() && FrameGenerationSettings.current().enabled(),
                        "saved On and launch flags request actual interpolation");
            } finally {
                MetalConfig.INSTANCE.enableFrameGeneration = saved;
                if (newFlag == null) System.clearProperty("metalmod.frameGeneration");
                else System.setProperty("metalmod.frameGeneration", newFlag);
                if (legacyFlag == null) System.clearProperty("metalmod.displayLink");
                else System.setProperty("metalmod.displayLink", legacyFlag);
            }
            directory = Files.createTempDirectory("metalmod-frame-generation-config-");
            var file = directory.resolve("metalmod.properties");
            Files.writeString(file,"enableSuperResolution=true\nsuperResolutionStrength=33\n");
            var missing = new MetalConfig(file.toFile());
            missing.load();
            require(!missing.enableFrameGeneration && missing.enableSuperResolution, "missing FG key Off; SR unchanged");
            missing.enableFrameGeneration = true;
            missing.save();
            var onReload = new MetalConfig(file.toFile());
            onReload.load();
            require(onReload.enableFrameGeneration && onReload.enableSuperResolution && onReload.superResolutionStrength==33,
                    "On survives actual save/reload without changing SR");
            onReload.enableFrameGeneration = false;
            onReload.save();
            var offReload = new MetalConfig(file.toFile());
            offReload.load();
            require(!offReload.enableFrameGeneration && offReload.enableSuperResolution, "Off survives save/reload");
            Files.writeString(file,"enableFrameGeneration=invalid\n");
            offReload.load();
            require(!offReload.enableFrameGeneration, "malformed persisted boolean falls back Off");
            System.out.println("PASS Frame Generation preference precedence, immutable snapshots and config On/Off round trips");
            return 0;
        } catch (Exception | AssertionError error) {
            error.printStackTrace(); return 1;
        } finally {
            if (directory != null) try {
                Files.deleteIfExists(directory.resolve("metalmod.properties"));
                Files.deleteIfExists(directory);
            } catch (java.io.IOException error) { throw new RuntimeException(error); }
        }
    }
}
