package net.metalmod.upscaling;

import org.joml.Matrix4fc;

/** Scene projection for optional renderers that upload their own camera uniforms. */
public interface TemporalProjectionAccess {
    /** Applies the current scene sample without mutating the renderer's unjittered camera matrix. */
    Matrix4fc metalmod$sceneProjection(Matrix4fc projection);
}
