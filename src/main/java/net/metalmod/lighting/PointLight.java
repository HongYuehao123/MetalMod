package net.metalmod.lighting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One dynamic point light, in world space, with the camera origin kept in doubles.
 *
 * <p>Intensity is an artistic unit calibrated against a reference torch, not lumens, and it is
 * bounded to {@code [0, 1]} so a misconfigured source cannot blow out the frame. Colour channels are
 * bounded the same way. All validation happens in the canonical constructor, so an invalid light
 * cannot be constructed and therefore cannot reach the GPU.
 *
 * <p>{@link #relativeTo} subtracts the frame camera origin <em>before</em> narrowing to float. A
 * light 30 million blocks out and the terrain it lights therefore round identically, which is what
 * keeps the illumination attached to the block instead of swimming as the camera moves.
 */
public record PointLight(double x, double y, double z, float radius,
                         float red, float green, float blue, float intensity) {

    /** GLSL block name. Matches the injected declaration in the terrain fragment variant. */
    public static final String UNIFORM = "MetalModPointLight";

    /** std140: vec4 positionRadius, vec4 colorIntensity. */
    public static final int BYTES = 32;

    public PointLight {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(radius) || radius <= 0
                || !channel(red) || !channel(green) || !channel(blue)
                || !Float.isFinite(intensity) || intensity < 0 || intensity > 1) {
            throw new IllegalArgumentException("Invalid point light");
        }
    }

    private static boolean channel(float value) {
        return Float.isFinite(value) && value >= 0 && value <= 1;
    }

    /** Encode this light relative to a camera origin, in the std140 layout the shader declares. */
    public ByteBuffer relativeTo(double originX, double originY, double originZ) {
        float rx = relative(x, originX);
        float ry = relative(y, originY);
        float rz = relative(z, originZ);
        return ByteBuffer.allocateDirect(BYTES).order(ByteOrder.nativeOrder())
                .putFloat(rx).putFloat(ry).putFloat(rz).putFloat(radius)
                .putFloat(red).putFloat(green).putFloat(blue).putFloat(intensity)
                .flip();
    }

    private static float relative(double value, double origin) {
        float narrowed = (float) (value - origin);
        if (!Double.isFinite(origin) || !Float.isFinite(narrowed)) {
            throw new IllegalArgumentException("Invalid camera-relative light position");
        }
        return narrowed;
    }
}
