package net.metalmod.metalfx;

/** Launch-only experiments, separate from user quality settings and capability selection. */
record TemporalDiagnostics(double outputScale, boolean splitEncode, boolean skipMotion) {
    static TemporalDiagnostics fromLaunch() {
        double scale = 0.0; // History at world size; Spatial finishes to the output size.
        try {
            double value = Double.parseDouble(System.getProperty("metalmod.temporalOutputScale", "0"));
            if (Double.isFinite(value) && value >= 0.0 && value <= 1.0) scale = value;
        } catch (NumberFormatException ignored) { }
        boolean skip = Boolean.getBoolean("metalmod.skipMotionEncode");
        if (skip) System.err.println("[MetalMod] diagnostic skipMotionEncode: motion is invalid; "
                + "this run cannot be used to evaluate image quality");
        return new TemporalDiagnostics(scale, Boolean.getBoolean("metalmod.splitTemporalEncode"), skip);
    }
}
