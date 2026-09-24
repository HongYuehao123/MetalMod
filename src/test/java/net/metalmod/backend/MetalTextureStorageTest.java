package net.metalmod.backend;

import com.mojang.blaze3d.textures.GpuTexture;

/**
 * Pins the rule that decides a texture's Metal storage mode.
 *
 * <p>The rule is a claim about which textures the engine touches from the CPU, so it is the part
 * worth testing: get it wrong in the permissive direction and a render target loses its bandwidth
 * advantage, get it wrong in the aggressive direction and an upload disappears into GPU-private
 * memory. The device-dependent half is covered by the native smoke test, which renders into a
 * private target and reads the result back through a blit.
 */
public final class MetalTextureStorageTest {

    private static int failures;

    private static void check(String what, boolean condition, String detail) {
        if (condition) {
            System.out.println("PASS " + what);
        } else {
            failures++;
            System.out.println("FAIL " + what + (detail.isEmpty() ? "" : " -> " + detail));
        }
    }

    public static int runTests() {
        System.out.println("[TEST] Texture storage mode rule");
        failures = 0;

        int copyDst = GpuTexture.USAGE_COPY_DST;
        int copySrc = GpuTexture.USAGE_COPY_SRC;
        int binding = GpuTexture.USAGE_TEXTURE_BINDING;
        int render = GpuTexture.USAGE_RENDER_ATTACHMENT;

        check("a writeToTexture target keeps CPU-visible storage",
                MetalTexture.needsSharedStorage(copyDst), "");
        check("a readback source keeps CPU-visible storage",
                MetalTexture.needsSharedStorage(copySrc), "");
        check("both copy flags together keep CPU-visible storage",
                MetalTexture.needsSharedStorage(copyDst | copySrc), "");
        check("a plain render attachment does not need CPU-writable storage",
                !MetalTexture.needsSharedStorage(render), "");
        check("a sampled render attachment does not need CPU-writable storage",
                !MetalTexture.needsSharedStorage(render | binding), "");
        check("a sampled-only texture does not need CPU-writable storage",
                !MetalTexture.needsSharedStorage(binding), "");
        check("the texel-buffer emulation counts as a copy destination",
                MetalTexture.needsSharedStorage(binding | copyDst), "");
        check("an uploaded copy source stays CPU-visible",
                MetalTexture.needsSharedStorage(binding | copyDst | copySrc), "");

        // The switch may only ever make storage more conservative. This must hold however the
        // process was launched, so it is asserted over every combination rather than one sample.
        boolean safe = true;
        String offender = "";
        int[] flags = {0, copyDst, copySrc, binding, render};
        for (int a : flags) {
            for (int b : flags) {
                int usage = a | b;
                if (MetalTexture.needsSharedStorage(usage) && !MetalTexture.usesSharedStorage(usage)) {
                    safe = false;
                    offender = "usage=" + usage;
                }
            }
        }
        check("a usage that needs shared storage is never given private storage", safe, offender);

        // With the feature on, a render attachment is the resource the change is for.
        boolean enabled = MetalTexture.privateTexturesEnabled();
        check("the render-attachment decision follows the switch",
                MetalTexture.usesSharedStorage(render) != enabled,
                "privateTextures=" + enabled);

        System.out.println(failures == 0 ? "[TEST] storage mode rule OK" : "[TEST] " + failures + " FAILED");
        return failures;
    }

    private MetalTextureStorageTest() {
    }
}
