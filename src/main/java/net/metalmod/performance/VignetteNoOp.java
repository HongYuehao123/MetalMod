package net.metalmod.performance;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import net.metalmod.backend.MetalDevice;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Skip only a zero-RGB draw through the exact verified vanilla shader pair and blend equation. */
public final class VignetteNoOp {
    // Minecraft 26.2 SPIRV-Cross output, verified by an actual textured-vignette pixel test.
    private static final String VERIFIED_PAIR = "6af0c2a73db353c5d169ae6c02a7486c237c21d783ebb9ab97be549bf668a802";
    private static volatile boolean verified;
    private VignetteNoOp() {}

    public static void reset() { verified = false; }
    public static boolean verified() { return verified; }

    public static void observe(RenderPipeline pipeline, String vertexMsl, String fragmentMsl) {
        if (pipeline != net.minecraft.client.renderer.RenderPipelines.VIGNETTE) return;
        String fingerprint = fingerprint(vertexMsl, fragmentMsl);
        if (Boolean.getBoolean("metalmod.dumpVignetteProof"))
            System.out.println("[MetalMod] vignette shader fingerprint " + fingerprint);
        var target = pipeline.getColorTargetState();
        var blend = target == null ? null : target.blendFunction().orElse(null);
        verified = VERIFIED_PAIR.equals(fingerprint) && !pipeline.wantsDepthTexture() && blend != null
                && blend.color().sourceFactor() == BlendFactor.ZERO
                && blend.color().destFactor() == BlendFactor.ONE_MINUS_SRC_COLOR
                && blend.color().op() == BlendOp.ADD
                && blend.alpha().sourceFactor() == BlendFactor.ZERO
                && blend.alpha().destFactor() == BlendFactor.ONE
                && blend.alpha().op() == BlendOp.ADD;
    }

    public static boolean canSkip(RenderPipeline pipeline, int argb) {
        return verified && MetalDevice.active() != null
                && pipeline == net.minecraft.client.renderer.RenderPipelines.VIGNETTE
                && (argb & 0x00ffffff) == 0
                // The proof covers the ordinary colour target. Keep enhanced composition paths
                // on their existing draw/coverage contract until each has its own skip proof.
                && !net.metalmod.upscaling.UpscalingSettings.current().enabled()
                && !net.metalmod.upscaling.FrameGenerationSettings.current().enabled()
                && !net.metalmod.upscaling.FrameGenerationCoordinator.rasterActive()
                && Boolean.parseBoolean(System.getProperty("metalmod.skipBlackVignette", "true"));
    }

    private static String fingerprint(String vertex, String fragment) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(vertex.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return HexFormat.of().formatHex(digest.digest(fragment.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
