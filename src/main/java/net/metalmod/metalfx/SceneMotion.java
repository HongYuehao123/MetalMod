package net.metalmod.metalfx;

import net.metalmod.backend.MetalDevice;
import net.metalmod.backend.MetalNative;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.HashMap;
import java.util.Map;

/**
 * The shared current/previous-frame scene contract a temporal scaler needs, and the motion-vector
 * producer built on it.
 *
 * <p><b>What a temporal scaler needs.</b> MetalFX reconstructs each output pixel from where its
 * surface was in previous frames. It cannot derive that itself: it takes a motion texture, and the
 * documentation is explicit that depth reprojection on its own describes camera motion but not
 * independently moving objects. So the backend has to publish motion.
 *
 * <p><b>What this publishes.</b> Both halves of that field, from the two sources that can supply
 * them. The camera half comes from depth: each pixel's depth is turned back into a camera-relative
 * world position and reprojected through the previous frame's view-projection, which is exact for
 * static geometry under any camera motion. The object half comes from the engine's own rendered
 * positions: entities, particles and pushed blocks are interpolated every frame, so their previous
 * positions are known exactly, and the native overlay stamps their motion over the dispatch's answer
 * inside a screen-space box that a depth test keeps off surfaces the object is not in front of.
 *
 * <p>What is left uncovered is geometry the engine does not describe as an object: a block that
 * changes shape in place, or a surface whose change is a texture animation rather than a position.
 * Those pixels keep the camera's answer, and their motion is genuinely zero, so they reproject
 * correctly - nothing here guesses at a velocity it was not given.
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

    // ---------------------------------------------------------------------------------------------
    // Moving geometry, for the screen-space overlay
    // ---------------------------------------------------------------------------------------------
    //
    // The dispatch above describes anything that was where it is now relative to the camera. It
    // cannot describe an entity, a particle or a pushed block, because the depth buffer only holds
    // this frame: those surfaces reproject as if they had never moved. Their own previous rendered
    // positions are known - the engine interpolates them every frame - so they are captured here and
    // handed to the native overlay as screen-space stamps.

    /**
     * Visible entities one frame can describe. Well above any normal scene; a scene past it loses the
     * tail rather than the frame, and the count is reported.
     */
    private static final int ENTITY_CAPACITY = 512;

    /** x, y, z as world coordinates, width, height, previous x/y/z, and a has-previous flag. */
    private static final int ENTITY_STRIDE = 9;

    private static final float[] entitySamples = new float[ENTITY_CAPACITY * ENTITY_STRIDE];
    private static int entityCount;
    private static Map<Integer, float[]> entityHistory = new HashMap<>();
    private static Map<Integer, float[]> entityHistoryNext = new HashMap<>();

    /**
     * Moving blocks - a piston's pushed block, which is a block entity whose rendered position is its
     * block position plus an interpolated offset.
     *
     * <p>Keyed by packed block position rather than by entity id, and a separate table because the two
     * id spaces overlap: a packed position can be any long, and the two must not be able to alias.
     */
    private static Map<Long, float[]> blockHistory = new HashMap<>();
    private static Map<Long, float[]> blockHistoryNext = new HashMap<>();

    /** The particle budget. A rain shower is the case that reaches it. */
    private static final int PARTICLE_CAPACITY = 2048;

    /**
     * x, y, z and the quad size, then the previous camera-relative position.
     *
     * <p>Camera-relative rather than world, because that is the form the particle extractor already
     * has and converting it here would mean re-deriving the interpolation it just did. The frame's
     * camera displacement is subtracted when the stamp is built, which is the same correction the
     * matrices apply to everything else.
     */
    private static final int PARTICLE_STRIDE = 7;

    private static final float[] particleSamples = new float[PARTICLE_CAPACITY * PARTICLE_STRIDE];
    private static int particleCount;

    /** Eight floats per stamp, matching {@code MMMMotionStamp}. */
    private static final int STAMP_FLOATS = 8;

    private static float[] stampValues = new float[STAMP_FLOATS * 64];
    private static MemorySegment stampSegment;
    private static int stampCount;
    private static int stampsDropped;
    private static long stampsDrawn;
    private static long objectsSkipped;
    private static final Vector4f projected = new Vector4f();
    private static final Vector4f currentPixel = new Vector4f();
    private static final Vector4f previousPixel = new Vector4f();

    /**
     * Below this the object's own motion is smaller than the jitter, and the camera's answer is
     * already better than a constant stamp would be.
     */
    private static final float MIN_OBJECT_MOTION_PIXELS = 0.35f;

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
        entityCount = 0;
        particleCount = 0;
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
            // No trustworthy pairing, so the scaler must not accumulate across the gap - and with no
            // matrices there is nothing to project an object with either.
            havePrevious = false;
            clearStamps();
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
            // motion exactly zero, which is the honest answer - and with no previous camera there is
            // no object motion to draw either, so the stamps are cleared rather than guessed.
            currentViewProjection.get(previousForwardValues);
            matricesReady = true;
            clearStamps();
            advanceMotionHistory();
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
        // Before rememberFrame, which overwrites the camera and would leave the stamps no camera
        // displacement to correct the particles with.
        buildStamps(renderWidth, renderHeight);
        advanceMotionHistory();
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
    // Capture: the moving geometry the depth buffer cannot describe
    // ---------------------------------------------------------------------------------------------

    /**
     * Record one visible entity's rendered position, as the engine interpolated it this frame.
     *
     * <p>Called from the extraction hook with the entity's own identity, so the previous frame's
     * position is an exact lookup rather than a guess at which entity is which.
     */
    public static void recordEntity(int id, double x, double y, double z,
                                    float width, float height, boolean invisible) {
        if (invisible) {
            return;
        }
        recordBox(entityHistory, entityHistoryNext, id, x, y, z, width, height);
    }

    /**
     * Record one moving block's rendered position.
     *
     * <p>A pushed block is a full cube whose block position does not change while it moves, so the
     * identity is the block position and the movement is carried entirely by the piston's interpolated
     * offset - which is exactly the pair this captures.
     */
    public static void recordBlock(long packedBlockPos, double minX, double minY, double minZ,
                                   float size) {
        recordBox(blockHistory, blockHistoryNext, packedBlockPos,
                minX + size * 0.5, minY, minZ + size * 0.5, size, size);
    }

    /**
     * Append one axis-aligned box, with the previous frame's centre looked up by identity.
     *
     * <p>The stored form is the box's centre on x and z and its minimum on y, with a width and a
     * height, which is what both an entity's bounding box and a moving block are once the sizes are
     * filled in. The history table is rebuilt every frame and swapped, so an object that despawns
     * costs nothing and the table never grows past one frame's set.
     */
    private static <K> void recordBox(Map<K, float[]> history, Map<K, float[]> next,
                                      K key, double x, double y, double z,
                                      float width, float height) {
        if (entityCount >= ENTITY_CAPACITY) {
            return;
        }
        int base = entityCount * ENTITY_STRIDE;
        entitySamples[base] = (float) x;
        entitySamples[base + 1] = (float) y;
        entitySamples[base + 2] = (float) z;
        entitySamples[base + 3] = width;
        entitySamples[base + 4] = height;
        float[] previous = history.get(key);
        entitySamples[base + 8] = previous == null ? 0.0f : 1.0f;
        if (previous != null) {
            entitySamples[base + 5] = previous[0];
            entitySamples[base + 6] = previous[1];
            entitySamples[base + 7] = previous[2];
        }
        float[] stored = next.get(key);
        if (stored == null) {
            stored = new float[3];
            next.put(key, stored);
        }
        stored[0] = (float) x;
        stored[1] = (float) y;
        stored[2] = (float) z;
        entityCount++;
    }

    /**
     * Record one particle's camera-relative rendered position and the one before it.
     *
     * <p>Particles are recorded as a camera-relative pair rather than a world position because the
     * extractor has already produced that form; the frame's camera displacement is applied when the
     * stamp is built, which is the same correction the matrices carry for everything else.
     */
    public static void recordParticle(float x, float y, float z, float size,
                                      float previousX, float previousY, float previousZ) {
        if (particleCount >= PARTICLE_CAPACITY) {
            return;
        }
        int base = particleCount * PARTICLE_STRIDE;
        particleSamples[base] = x;
        particleSamples[base + 1] = y;
        particleSamples[base + 2] = z;
        particleSamples[base + 3] = size;
        particleSamples[base + 4] = previousX;
        particleSamples[base + 5] = previousY;
        particleSamples[base + 6] = previousZ;
        particleCount++;
    }

    private static void advanceMotionHistory() {
        Map<Integer, float[]> previousEntities = entityHistory;
        entityHistory = entityHistoryNext;
        entityHistoryNext = previousEntities;
        entityHistoryNext.clear();
        Map<Long, float[]> previousBlocks = blockHistory;
        blockHistory = blockHistoryNext;
        blockHistoryNext = previousBlocks;
        blockHistoryNext.clear();
    }

    // ---------------------------------------------------------------------------------------------
    // Building the stamps
    // ---------------------------------------------------------------------------------------------

    /**
     * Turn this frame's captured objects into screen-space stamps and hand them to the native side.
     *
     * <p>An entity's depth <em>is</em> in the depth buffer, so its stamp only has to correct the
     * difference between where it is and where it was, and a stamp below the jitter is not worth
     * drawing. A particle's is not: particles do not write depth, so the depth-derived motion at a
     * particle's pixels belongs to whatever is behind it and is wrong for the particle by however much
     * their depths differ - which means every particle gets a stamp, including a stationary one.
     */
    private static void buildStamps(int width, int height) {
        stampCount = 0;
        stampsDropped = 0;
        if (!matricesReady || motion.address() == 0 || width <= 0 || height <= 0) {
            clearStamps();
            return;
        }
        int capacity = MetalNative.motionStampCapacity(motion);
        if (capacity <= 0) {
            clearStamps();
            return;
        }
        if (stampValues.length < capacity * STAMP_FLOATS) {
            stampValues = new float[capacity * STAMP_FLOATS];
        }

        float cameraX = (float) frameCameraX;
        float cameraY = (float) frameCameraY;
        float cameraZ = (float) frameCameraZ;
        float cameraDeltaX = (float) (frameCameraX - previousCameraX);
        float cameraDeltaY = (float) (frameCameraY - previousCameraY);
        float cameraDeltaZ = (float) (frameCameraZ - previousCameraZ);
        // The projection's own y scale, taken from the unjittered matrix: M1 is P * V, so its m11
        // mixes the camera's rotation into the term that answers "how many pixels is a world unit at
        // this depth", and using it would make a particle's size depend on where the player is
        // looking.
        float pixelsPerUnitY = 0.5f * height * frameUnjittered.m11();

        for (int index = 0; index < entityCount; index++) {
            int base = index * ENTITY_STRIDE;
            if (entitySamples[base + 8] < 0.5f) {
                objectsSkipped++;
                continue;
            }
            float halfWidth = entitySamples[base + 3] * 0.5f;
            float boxHeight = entitySamples[base + 4];
            float relativeX = entitySamples[base] - cameraX;
            float relativeY = entitySamples[base + 1] - cameraY;
            float relativeZ = entitySamples[base + 2] - cameraZ;

            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            float minDepth = Float.MAX_VALUE;
            float maxDepth = -Float.MAX_VALUE;
            boolean projectedAll = true;
            for (int corner = 0; corner < 8; corner++) {
                float cornerX = relativeX + ((corner & 1) == 0 ? -halfWidth : halfWidth);
                float cornerY = relativeY + ((corner & 2) == 0 ? 0.0f : boxHeight);
                float cornerZ = relativeZ + ((corner & 4) == 0 ? -halfWidth : halfWidth);
                if (!projectPixel(currentViewProjection, cornerX, cornerY, cornerZ, width, height,
                        projected)) {
                    projectedAll = false;
                    break;
                }
                minX = Math.min(minX, projected.x);
                maxX = Math.max(maxX, projected.x);
                minY = Math.min(minY, projected.y);
                maxY = Math.max(maxY, projected.y);
                minDepth = Math.min(minDepth, projected.z);
                maxDepth = Math.max(maxDepth, projected.z);
            }
            if (!projectedAll) {
                // Partly behind the camera: no box describes it, so it keeps the depth-derived
                // answer rather than a guess.
                objectsSkipped++;
                continue;
            }
            if (maxX < 0.0f || minX > width || maxY < 0.0f || minY > height) {
                continue;
            }
            if (!projectPixel(currentViewProjection, relativeX, relativeY + boxHeight * 0.5f,
                    relativeZ, width, height, currentPixel)) {
                continue;
            }
            if (!projectPixel(previousViewProjection,
                    entitySamples[base + 5] - cameraX,
                    entitySamples[base + 6] - cameraY,
                    entitySamples[base + 7] - cameraZ, width, height, previousPixel)) {
                objectsSkipped++;
                continue;
            }
            float motionX = previousPixel.x - currentPixel.x;
            float motionY = previousPixel.y - currentPixel.y;
            if (Math.abs(motionX) < MIN_OBJECT_MOTION_PIXELS
                    && Math.abs(motionY) < MIN_OBJECT_MOTION_PIXELS) {
                continue;
            }
            emitStamp(minX, minY, maxX, maxY, motionX, motionY, minDepth, maxDepth, capacity);
        }

        for (int index = 0; index < particleCount; index++) {
            int base = index * PARTICLE_STRIDE;
            float relativeX = particleSamples[base];
            float relativeY = particleSamples[base + 1];
            float relativeZ = particleSamples[base + 2];
            float size = particleSamples[base + 3];
            if (!projectPixel(currentViewProjection, relativeX, relativeY, relativeZ,
                    width, height, currentPixel)) {
                continue;
            }
            float viewDepth = currentPixel.w;
            if (viewDepth <= 1e-4f) {
                continue;
            }
            // The stored previous position is relative to the previous camera, so the frame's own
            // displacement is taken out before it is projected.
            if (!projectPixel(previousViewProjection,
                    particleSamples[base + 4] - cameraDeltaX,
                    particleSamples[base + 5] - cameraDeltaY,
                    particleSamples[base + 6] - cameraDeltaZ, width, height, previousPixel)) {
                continue;
            }
            float motionX = previousPixel.x - currentPixel.x;
            float motionY = previousPixel.y - currentPixel.y;
            // Generous: the quad faces the camera and this is a screen-axis box around it, so
            // covering a little more than the quad is safer than leaving an edge behind. The depth
            // test keeps the extra area from taking the particle's motion off a nearer surface.
            float half = Math.max(1.0f, size * pixelsPerUnitY / viewDepth * 0.75f);
            emitStamp(currentPixel.x - half, currentPixel.y - half,
                    currentPixel.x + half, currentPixel.y + half,
                    motionX, motionY, currentPixel.z - 0.002f, 1.0f, capacity);
        }

        uploadStamps();
    }

    private static boolean projectPixel(Matrix4f matrix, float x, float y, float z,
                                        int width, int height, Vector4f out) {
        out.set(x, y, z, 1.0f);
        matrix.transform(out);
        if (out.w <= 1e-6f) {
            return false;
        }
        float inverse = 1.0f / out.w;
        float ndcX = out.x * inverse;
        float ndcY = out.y * inverse;
        float ndcZ = out.z * inverse;
        out.set((ndcX * 0.5f + 0.5f) * width, (0.5f - ndcY * 0.5f) * height, ndcZ, out.w);
        return true;
    }

    private static void emitStamp(float minX, float minY, float maxX, float maxY,
                                  float motionX, float motionY,
                                  float depthMin, float depthMax, int capacity) {
        if (stampCount >= capacity) {
            stampsDropped++;
            return;
        }
        int base = stampCount * STAMP_FLOATS;
        stampValues[base] = Math.max(0.0f, minX);
        stampValues[base + 1] = Math.max(0.0f, minY);
        stampValues[base + 2] = maxX;
        stampValues[base + 3] = maxY;
        stampValues[base + 4] = motionX;
        stampValues[base + 5] = motionY;
        stampValues[base + 6] = Math.max(0.0f, Math.min(1.0f, depthMin));
        stampValues[base + 7] = Math.max(0.0f, Math.min(1.0f, depthMax));
        stampCount++;
    }

    private static void uploadStamps() {
        if (motion.address() == 0) {
            return;
        }
        if (stampCount == 0) {
            clearStamps();
            return;
        }
        if (stampSegment == null || stampSegment.byteSize() < (long) stampCount * STAMP_FLOATS * 4L) {
            stampSegment = arena.allocate(ValueLayout.JAVA_FLOAT, stampCount * STAMP_FLOATS);
        }
        MemorySegment.copy(stampValues, 0, stampSegment, ValueLayout.JAVA_FLOAT, 0,
                stampCount * STAMP_FLOATS);
        MetalNative.motionSetStamps(motion, stampSegment, stampCount);
        stampsDrawn += stampCount;
    }

    private static void clearStamps() {
        stampCount = 0;
        stampsDropped = 0;
        if (motion.address() != 0) {
            MetalNative.motionSetStamps(motion, MemorySegment.NULL, 0);
        }
    }

    /** Objects captured this frame, and how many stamps reached the GPU (plus any dropped). */
    public static String objectSummary() {
        return "objects " + entityCount + " entities, " + particleCount + " particles, "
                + stampCount + " stamps" + (stampsDropped > 0 ? " (" + stampsDropped + " dropped)" : "");
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
        entityHistory.clear();
        entityHistoryNext.clear();
        blockHistory.clear();
        blockHistoryNext.clear();
        entityCount = 0;
        particleCount = 0;
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
            stampSegment = null;
        }
        havePrevious = false;
        matricesReady = false;
        entityHistory.clear();
        entityHistoryNext.clear();
        blockHistory.clear();
        blockHistoryNext.clear();
        entityCount = 0;
        particleCount = 0;
        stampCount = 0;
        stampsDropped = 0;
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
        return "motion " + motionWidth + "x" + motionHeight + " camera+objects, dispatched "
                + framesDispatched + ", " + objectSummary() + ", resets " + resets
                + (failures > 0 ? ", failed " + failures : "")
                + (lastFailure.isEmpty() ? "" : " (" + lastFailure + ")");
    }
}
