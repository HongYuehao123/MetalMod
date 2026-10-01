package net.metalmod.upscaling;

/** Meaningful configuration cases independent of Minecraft and GPU availability. */
public final class UpscalingSettingsTest {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static int runTests() {
        try {
            var file = UpscalingSettings.resolve(null,null,null,null,true,33,0);
            require(file.enabled() && file.scale()==67, "file settings");
            var flags = UpscalingSettings.resolve(null,null,"false","50",true,33,0);
            require(!flags.enabled() && flags.strength()==50 && flags.scale()==100, "flags beat file, Off remembers strength");
            var ui = UpscalingSettings.resolve(true,25,"false","50",false,50,2);
            require(ui.enabled() && ui.strength()==25 && ui.revision()==2, "UI beats flags");
            require(flags.strength()==50 && !flags.enabled(), "previous snapshot immutable");
            var nativeOn = new UpscalingSettings.Snapshot(true,0,0);
            require(UpscalingSettings.dimensions(3840,2160,nativeOn).equals(new UpscalingSettings.Dimensions(3840,2160)), "On 100% bypass");
            var dims = UpscalingSettings.dimensions(3840,2160,ui);
            require(dims.width()==2880 && dims.height()==1620 && dims.pixelPercent(3840,2160)==56.25, "75% dimensions and actual pixels");
            require(UpscalingSettings.dimensions(65,49,new UpscalingSettings.Snapshot(true,50,0))
                    .equals(new UpscalingSettings.Dimensions(32,24)), "odd sizes floor");
            require(UpscalingSettings.dimensions(0,2160,ui).equals(new UpscalingSettings.Dimensions(0,0)), "minimize suspends");
            for (String bad : new String[]{"", "-1", "24", "100", "25%", "NaN", "999999999999"})
                require(UpscalingSettings.parseStrength(bad)==25, "malformed strength " + bad);
            int[] cycle={0,25,33,50,0};
            for (int i=0;i<4;i++) require(UpscalingSettings.nextStrength(cycle[i])==cycle[i+1],"preset cycle");
            var stats = new MetalFxCoordinator.Stats(true,25,"MetalFX spatial","",2880,1620,3840,2160,1,2,3,4,5,6,7,8);
            long[] values={1,25,1,2880,1620,3840,2160,5625,1,2,3,4,5,6,7,8,-1,0};
            require(net.metalmod.debug.UpscalingCaptureColumns.NAMES.size()==values.length,"capture width");
            for (int i=0;i<values.length;i++) require(net.metalmod.debug.UpscalingCaptureColumns.value(stats,i)==values[i],"capture value " + i);
            require(net.metalmod.debug.UpscalingCaptureColumns.reasonCode("MetalFX encode failed -6; native next frame")==10,"capture failure reason");
            System.out.println("PASS Super Resolution settings: precedence, immutable snapshots, malformed config, presets and dimensions");
            return 0;
        } catch (AssertionError e) { e.printStackTrace(); return 1; }
    }
}
