package net.metalmod.metalfx;

import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalNative;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * The shared current/previous-frame scene contract a temporal scaler needs, and the motion-vector
 * producer built on it.
 *
 * <p><b>What a temporal scaler needs.</b> MetalFX reconstructs each output pixel from where its
 * surface was in previous frames. It cannot derive that itself: it takes a motion texture, and the
 * documentation is explicit that depth reprojection on its own describes camera motion but not
 * independently moving objects. So the backend has to publish motion.
 *
 * <p><b>What this publishes.</b> The camera half of that field. Each pixel's depth is turned back
 * into a camera-relative world position and reprojected through the previous frame's
 * view-projection, which is exact for static geometry under any camera motion. Geometry that moves
 * independently - a mob, a particle, an animated block - still reprojects as if it were static, and
 * those pixels are the gap Phase 8C's previous-transform contract fills. That limit is stated here
 * rather than hidden: the backend reports this as a camera-only producer, and the settings screen
 * says so.
 *
 * <p><b>Why a shared contract.</b> The matrices are the same ones Phase 8C needs for ray visibility
 * and ray-traced shadows: the current and previous view-projection, the camera's own displacement,
 * and the projection's jitter. Keeping them in one place means the two phases cannot disagree about
 * what "last frame" means.
 *
 * <h2>Conventions, all of which matter</h2>
 *
 * <ul>
 *   <li><b>Camera-relative world space.</b> Minecraft's level shaders build a camera-relative
 *       position and multiply it by a rotation-only view ({@code viewRotationMatrix}), so the
 *       view-projection here acts on that same space. A world point is not needed and a large world
 *       coordinate never reaches a float.</li>
 *   <li><b>Frame-to-frame translation is folded into the previous matrix.</b> The previous matrix is
 *       {@code P_prev * V_prev * T(cameraNow - cameraPrevious)}, so applying it to a current-relative
 *       position reproduces exactly what the point projected to last frame. No camera position is
 *       passed to the kernel at all.</li>
 *   <li><b>Unjittered.</b> MetalFX takes the sub-pixel offset separately in {@code jitterOffsetX/Y},
 *       and the descriptor's {@code jitteredMotionVectors} option is off, so the jitter this backend
 *       added to the projection is removed here before the matrices are built. Reconstruction and
 *       reprojection are therefore in the same unjittered reference frame, and the residual error is
 *       bounded by the jitter (under half a pixel) times the local depth gradient.</li>
 *   <li><b>Metal's depth convention.</b> The backend reports {@code isZZeroToOne}, so the projection
 *       maps near to 0 and far to 1, and the stored depth is the clip-space z directly. That is also
 *       the {@code depthReversed = false} the temporal scaler is created with.</li>
 * </ul>
 */
public final class SceneMotion {

    /**
     * A camera displacement this large between two frames is a teleport, a dimension change or a
     * respawn, not motion. Eight blocks in one frame is faster than any legitimate movement.
     */
    private static final double CUT_DISTANCE = 8.0;

    /**
     * A rotation this large between two frames is a cut. Compared as the largest element change in
     * the rotation matrix; 0.5 is roughly thirty degrees, well above a fast mouse flick and well
     * below a view flip or a camera switch.
     */
    private static final float CUT_ROTATION = 0.5f;

    // --- per-frame captures, written by the mixins ------------------------------------------------

    private static final Matrix4f frameProjection = new Matrix4f();
    private static final Matrix4f frameViewRotation = new Matrix4f();
    private static double frameCameraX;
    private static double frameCameraY;
    private static double frameCameraZ;
    private static boolean projectionCaptured;
    private static boolean cameraCaptured;

    // --- the previous frame ----------------------------------------------------------------------

    private static final Matrix4f previousProjection = new Matrix4f();
    private static final Matrix4f previousViewRotation = new Matrix4f();
    private static double previousCameraX;
    private static double previousCameraY;
    private static double previousCameraZ;
    private static boolean havePrevious;

    // --- the matrices handed to the kernel -------------------------------------------------------

    private static final Matrix4f currentViewProjection = new Matrix4f();
    private static final Matrix4f currentInverse = new Matrix4f();
    private static final Matrix4f previousViewProjection = new Matrix4f();

    /**
     * This frame's projection with the jitter taken back out.
     *
     * <p>Kept as its own value rather than derived twice, because the previous frame's copy is what
     * the next frame reprojects against: storing the <em>jittered</em> matrix and removing the
     * offset later would leave each frame's history one jitter phase out of step, which is a
     * sub-pixel discrepancy that a still camera turns into visible motion. Both frames have to be in
     * the same reference frame, so the removal happens once and the result is what is remembered.
     */
    private static final Matrix4f frameUnjittered = new Matrix4f();
    private static final float[] currentInverseValues = new float[16];
    private static final float[] previousForwardValues = new float[16];
    private static boolean matricesReady;

    private static Arena arena;
    private static MemorySegment currentInverseSegment;
    private static MemorySegment previousForwardSegment;

    // --- the native resource ---------------------------------------------------------------------

    private static MemorySegment motion = MemorySegment.NULL;
    private static int motionWidth;
    private static int motionHeight;
    private static long framesDispatched;
    private static long failures;
    private static long resets;
    private static String lastFailure = "";
    private static String lastResetReason = "";

    private SceneMotion() {
    }

    // ---------------------------------------------------------------------------------------------
    // Frame captures
    // ---------------------------------------------------------------------------------------------

    /**
     * Start a frame: nothing is captured yet, so a frame with no level cannot reuse the last one's
     * camera.
     *
     * <p>Called at the head of the engine's extraction, before the level's projection is built.
     */
    public static void beginFrame() {
        projectionCaptured = false;
        cameraCaptured = false;
        matricesReady = false;
    }

    /**
     * Record the projection matrix the level actually rendered with.
     *
     * <p>Hooked where the engine hands its level projection to the device, because that matrix is
     * the projection <em>after</em> the frame's bob and screen-effect transforms have been folded
     * into it. Rebuilding it from the camera state instead would omit those, and view bobbing would
     * then show up as several pixels of wrong motion at the edges of the screen - which is exactly
     * the case temporal upscaling is most visible in.
     */
    public static void captureProjection(Matrix4fc projection) {
        if (projection == null) {
            return;
        }
        frameProjection.set(projection);
        projectionCaptured = true;
    }

    /** Record the camera the level is about to be rendered from. */
    public static void captureCamera(CameraRenderState camera) {
        if (camera == null || camera.viewRotationMatrix == null || camera.pos == null) {
            cameraCaptured = false;
            return;
        }
        frameViewRotation.set(camera.viewRotationMatrix);
        frameCameraX = camera.pos.x;
        frameCameraY = camera.pos.y;
        frameCameraZ = camera.pos.z;
        cameraCaptured = true;
    }

    /** Whether this frame has both halves of the scene contract. */
    public static boolean captured() {
        return projectionCaptured && cameraCaptured;
    }

    // ---------------------------------------------------------------------------------------------
    // The matrices
    // ---------------------------------------------------------------------------------------------

    /**
     * Build this frame's two matrices for the given render size, and decide whether the temporal
     * history is still valid.
     *
     * @param renderWidth  the level target's width, which is the unit the projection jitter is in
     * @param renderHeight the level target's height
     */
    public static void prepare(int renderWidth, int renderHeight) {
        matricesReady = false;
        if (!captured() || renderWidth <= 0 || renderHeight <= 0) {
            // No trustworthy pairing, so the scaler must not accumulate across the gap.
            havePrevious = false;
            return;
        }

        // The projection carried the sub-pixel jitter the frame was rendered with. MetalFX takes
        // that offset separately, so it is removed here: post-multiplying by the inverse clip
        // translation is exact up to the frame's bob rotation, which is a second-order term of a
        // sub-pixel quantity.
        frameUnjittered.set(frameProjection);
        if (ProjectionJitter.appliedThisFrame()) {
            frameUnjittered.translate(-ProjectionJitter.clipX(renderWidth),
                    -ProjectionJitter.clipY(renderHeight), 0.0f);
        }

        // The inverse the kernel reconstructs with, and the forward matrix it reprojects with, are
        // both camera-relative. M1 = P * V turns a current-relative position into a clip position.
        currentViewProjection.set(frameUnjittered).mul(frameViewRotation);
        currentViewProjection.invert(currentInverse);
        currentInverse.get(currentInverseValues);

        if (!havePrevious) {
            requestReset("the first frame with a complete camera pair");
            // Nothing is known about where anything was, and a zero matrix would reproject the whole
            // image to the origin. Using this frame's own forward matrix makes the first frame's
            // motion exactly zero, which is the honest answer.
            currentViewProjection.get(previousForwardValues);
            matricesReady = true;
            rememberFrame();
            return;
        }

        if (isCut()) {
            requestReset(lastCutReason);
        }

        // previous: P_prev * V_prev * T(cameraNow - cameraPrevious). The translation is computed in
        // double and only then narrowed, because camera coordinates are large enough that subtracting
        // two floats would lose the sub-block part of a slow walk.
        double deltaX = frameCameraX - previousCameraX;
        double deltaY = frameCameraY - previousCameraY;
        double deltaZ = frameCameraZ - previousCameraZ;
        previousViewProjection.set(previousProjection)
                .mul(previousViewRotation)
                .translate((float) deltaX, (float) deltaY, (float) deltaZ);
        previousViewProjection.get(previousForwardValues);
        matricesReady = true;
        rememberFrame();
    }

    private static String lastCutReason = "";

    private static boolean isCut() {
        double deltaX = frameCameraX - previousCameraX;
        double deltaY = frameCameraY - previousCameraY;
        double deltaZ = frameCameraZ - previousCameraZ;
        double distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);
        if (distance > CUT_DISTANCE) {
            lastCutReason = String.format(java.util.Locale.ROOT,
                    "the camera moved %.1f blocks in one frame", distance);
            return true;
        }
        float worst = 0.0f;
        for (int column = 0; column < 3; column++) {
            for (int row = 0; row < 3; row++) {
                worst = Math.max(worst, Math.abs(frameViewRotation.get(column, row)
                        - previousViewRotation.get(column, row)));
            }
        }
        if (worst > CUT_ROTATION) {
            lastCutReason = String.format(java.util.Locale.ROOT,
                    "the camera turned (rotation changed by %.2f)", worst);
            return true;
        }
        return false;
    }

    private static void rememberFrame() {
        previousProjection.set(frameUnjittered);
        previousViewRotation.set(frameViewRotation);
        previousCameraX = frameCameraX;
        previousCameraY = frameCameraY;
        previousCameraZ = frameCameraZ;
        havePrevious = true;
    }

    /** Invalidate the history at the next encode. Used by everything that is not a camera cut. */
    public static void requestReset(String reason) {
        lastResetReason = reason;
        ProjectionJitter.requestReset();
        resets++;
    }

    /** Whether this frame's matrices were built. */
    public static boolean ready() {
        return matricesReady;
    }

    // ---------------------------------------------------------------------------------------------
    // The native resource and the dispatch
    // ---------------------------------------------------------------------------------------------

    /**
     * Create or replace the motion texture for this render size.
     *
     * <p>Returns false with the reason recorded when the resource cannot be had, so the caller can
     * fall back to Spatial instead of presenting nothing.
     */
    public static synchronized boolean ensureResource(MetalDevice device, int width, int height) {
        if (device == null || width <= 0 || height <= 0) {
            lastFailure = "no device or no render size";
            return false;
        }
        if (!MetalNative.motionAvailable()) {
            lastFailure = "the native library has no motion-vector producer";
            return false;
        }
        if (motion.address() != 0 && motionWidth == width && motionHeight == height) {
            return true;
        }
        releaseResource();
        MemorySegment created = MetalNative.motionCreate(device.deviceHandle(), width, height);
        if (created == null || created.address() == 0) {
            String reason = MetalNative.motionLastError();
            lastFailure = reason.isEmpty() ? "the motion texture could not be created" : reason;
            return false;
        }
        motion = created;
        motionWidth = width;
        motionHeight = height;
        if (arena == null) {
            arena = Arena.ofConfined();
            currentInverseSegment = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
            previousForwardSegment = arena.allocate(ValueLayout.JAVA_FLOAT, 16);
        }
        // A new texture has no history, and neither does the scaler that consumed the old one.
        havePrevious = false;
        lastFailure = "";
        System.out.println("[MetalMod] motion vectors: " + width + "x" + height
                + " (camera reprojection from depth)");
        return true;
    }

    private static void releaseResource() {
        if (motion.address() != 0) {
            MetalNative.motionRelease(motion);
        }
        motion = MemorySegment.NULL;
        motionWidth = 0;
        motionHeight = 0;
    }

    /** The motion texture the temporal scaler reads, or NULL. */
    public static MemorySegment texture() {
        return motion.address() == 0 ? MemorySegment.NULL : MetalNative.motionTexture(motion);
    }

    /**
     * Dispatch this frame's motion vectors, reading the level's depth.
     *
     * <p>Every failure is recorded rather than thrown: a frame without motion is a frame the caller
     * falls back on, not a frame that takes the game down.
     */
    public static int dispatch(MetalDevice device, MemorySegment depthTexture) {
        if (device == null || motion.address() == 0) {
            lastFailure = "no motion resource";
            return -1;
        }
        if (!matricesReady) {
            lastFailure = "no complete camera pair this frame";
            return -1;
        }
        if (depthTexture == null || depthTexture.address() == 0) {
            lastFailure = "the level depth texture had no Metal texture behind it";
            return -1;
        }
        MemorySegment.copy(currentInverseValues, 0, currentInverseSegment,
                ValueLayout.JAVA_FLOAT, 0, 16);
        MemorySegment.copy(previousForwardValues, 0, previousForwardSegment,
                ValueLayout.JAVA_FLOAT, 0, 16);
        try {
            int result = MetalNative.motionRun(motion, device.queueHandle(), depthTexture,
                    currentInverseSegment, previousForwardSegment);
            if (result != 0) {
                lastFailure = MetalNative.motionLastError();
                if (lastFailure.isEmpty()) {
                    lastFailure = "the motion dispatch returned " + result;
                }
                failures++;
                return result;
            }
            lastFailure = "";
            framesDispatched++;
            return 0;
        } catch (Throwable t) {
            lastFailure = String.valueOf(t);
            failures++;
            return -1;
        }
    }

    /** Whether a depth format can be read by the motion kernel. */
    public static boolean depthFormatSupported(MetalDevice device, long depthFormat) {
        if (device == null) {
            return false;
        }
        return MetalNative.motionDepthFormatSupported(device.deviceHandle(), depthFormat);
    }

    /** Forget the camera history. Called when the device is torn down or a world is left. */
    public static synchronized void clearHistory() {
        havePrevious = false;
        matricesReady = false;
        lastResetReason = "the world or device changed";
        ProjectionJitter.requestReset();
    }

    /** Drop the native resource and the history. */
    public static synchronized void close() {
        releaseResource();
        if (arena != null) {
            arena.close();
            arena = null;
            currentInverseSegment = null;
            previousForwardSegment = null;
        }
        havePrevious = false;
        matricesReady = false;
    }

    // ---------------------------------------------------------------------------------------------
    // Diagnostics
    // ---------------------------------------------------------------------------------------------

    public static String lastFailure() {
        return lastFailure;
    }

    public static long frameCount() {
        return framesDispatched;
    }

    public static long failureCount() {
        return failures;
    }

    public static long resetCount() {
        return resets;
    }

    public static String lastResetReason() {
        return lastResetReason;
    }

    public static int width() {
        return motionWidth;
    }

    public static int height() {
        return motionHeight;
    }

    /** One line for the F3 section: what the producer is, and what it has done. */
    public static String summary() {
        if (motion.address() == 0) {
            return "motion: none" + (lastFailure.isEmpty() ? "" : " (" + lastFailure + ")");
        }
        return "motion " + motionWidth + "x" + motionHeight + " camera-only, dispatched "
                + framesDispatched + ", resets " + resets
                + (failures > 0 ? ", failed " + failures : "")
                + (lastFailure.isEmpty() ? "" : " (" + lastFailure + ")");
    }
}
