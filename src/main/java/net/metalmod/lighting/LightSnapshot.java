package net.metalmod.lighting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Immutable render-frame light set. Sources and the camera use the same world-space frame. */
public record LightSnapshot(double cameraX, double cameraY, double cameraZ,
                            List<PointLight> lights) {
    /** GLSL block name for the bounded light-set uniform. Matches the injected declaration. */
    public static final String UNIFORM = "MetalModLightSet";
    /** Published record version, so a consumer can tell which layout it is reading. */
    public static final int ABI_VERSION = 1;
    /**
     * How many sources one frame may publish.
     *
     * <p>This is the bound on lights that <em>exist</em> at once, not on lights that light one place:
     * the collector gathers sources within {@link LightCollector#SEARCH_RADIUS} of the camera and keeps
     * the strongest this many, and the cluster grid then decides which of them reach any given cell.
     * The two bounds are independent - the cell capacity is what limits per-pixel work - so raising
     * this costs coverage sorting and upload size rather than shader iterations, except in the flat
     * path, whose fragment loop does run once per published light.
     */
    public static final int CAPACITY = 64;
    public static final int BYTES = 16 + CAPACITY * PointLight.BYTES;

    public LightSnapshot {
        lights = List.copyOf(lights);
        if (lights.size() > CAPACITY) throw new IllegalArgumentException("Too many lights");
    }

    public static LightSnapshot empty() { return new LightSnapshot(0, 0, 0, List.of()); }

    /** std140: ivec4(count, version, reserved, reserved), then two vec4s per light. */
    public ByteBuffer encode() {
        ByteBuffer out = ByteBuffer.allocateDirect(BYTES).order(ByteOrder.nativeOrder());
        out.putInt(lights.size()).putInt(ABI_VERSION).putInt(0).putInt(0);
        for (PointLight light : lights) out.put(light.relativeTo(cameraX, cameraY, cameraZ));
        out.position(0);
        out.limit(BYTES);
        return out;
    }
}
