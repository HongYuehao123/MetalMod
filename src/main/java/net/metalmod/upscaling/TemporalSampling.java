package net.metalmod.upscaling;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/** ABI v1 sampling math, independent of camera extraction and the active spatial path. */
public final class TemporalSampling {
    private TemporalSampling() {}
    public record Offset(float x, float y) {}

    /** A repeatable 16-sample Halton(2,3) sequence in top-left scene-pixel coordinates. */
    public static Offset jitter(long renderedFrame) {
        if (renderedFrame < 0) throw new IllegalArgumentException("negative rendered frame");
        int sample = (int) (renderedFrame % 16) + 1;
        return new Offset(radicalInverse(sample, 2) - 0.5f, radicalInverse(sample, 3) - 0.5f);
    }

    private static float radicalInverse(int sample, int base) {
        float result = 0, weight = 1.0f / base;
        while (sample > 0) {
            result += (sample % base) * weight;
            sample /= base;
            weight /= base;
        }
        return result;
    }

    /** Shift a copy of the projection so fixed pixel centres sample at +jitter.
     * In Metal's top-left viewport, geometry therefore shifts by -jitter pixels.
     * Left-multiplication adds clip.w-scaled offsets for perspective and orthographic matrices;
     * changing only m20/m21 would fail for orthographic or composed projections. */
    public static Matrix4f jitterProjection(Matrix4fc projection, Offset jitter, int width, int height) {
        if (width <= 0 || height <= 0 || !Float.isFinite(jitter.x()) || !Float.isFinite(jitter.y())
                || Math.abs(jitter.x()) > 0.5f || Math.abs(jitter.y()) > 0.5f)
            throw new IllegalArgumentException("invalid jitter or scene size");
        return new Matrix4f().translation(-2 * jitter.x() / width, 2 * jitter.y() / height, 0)
                .mul(projection);
    }

    /** Previous minus current unjittered NDC, converted to top-left scene pixels.
     * Callers must use each frame's own camera-relative origin and render-time object transform.
     * Missing/behind-camera previous geometry must be rejected by the reactive-mask producer. */
    public static Offset motion(float currentX, float currentY, float previousX, float previousY,
                                int width, int height) {
        if (width <= 0 || height <= 0 || !Float.isFinite(currentX) || !Float.isFinite(currentY)
                || !Float.isFinite(previousX) || !Float.isFinite(previousY))
            throw new IllegalArgumentException("invalid scene dimensions or NDC");
        float x = (previousX - currentX) * (width * 0.5f);
        float y = (currentY - previousY) * (height * 0.5f);
        if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("motion overflow");
        return new Offset(x, y);
    }
}
