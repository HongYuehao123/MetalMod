package net.metalmod.backend;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;

/**
 * Phase 1 pipeline placeholder.
 *
 * <p>It reports valid because ShaderManager.apply() throws when any precompiled pipeline is
 * invalid, and a placeholder cannot know whether the real shaders would compile. Draws through it
 * are inert, so a false positive has no visual consequence yet. Every placeholder is logged so the
 * list of pipelines still needing real compilation is visible.
 */
public final class MetalCompiledPipeline implements CompiledRenderPipeline {

    private final boolean valid;

    public MetalCompiledPipeline(boolean valid) {
        this.valid = valid;
    }

    @Override
    public boolean isValid() {
        return this.valid;
    }
}
