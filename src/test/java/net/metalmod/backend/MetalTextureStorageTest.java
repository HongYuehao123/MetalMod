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
        // What RenderTarget actually passes, bytecode-verified: bipush 15 for both the depth and the
        // colour texture. This is the case the rule exists for, so it is pinned to the literal the
        // engine uses rather than rebuilt from the constants.
        int renderTargetUsage = 15;

        check("the flag set Minecraft gives a render target is 15",
                renderTargetUsage == (copyDst | copySrc | binding | render), "");
        check("a render target is eligible for private storage",
                MetalTexture.privateEligible(renderTargetUsage), "");
        check("a depth attachment is eligible for private storage",
                MetalTexture.privateEligible(render), "");
        check("a render target with no sampling is eligible",
                MetalTexture.privateEligible(render), "");

        // The textures the engine uploads into. All of these must stay shared or the upload is lost.
        check("an uploaded atlas is not eligible", !MetalTexture.privateEligible(binding | copyDst), "");
        check("a dynamic texture is not eligible",
                !MetalTexture.privateEligible(binding | copyDst | copySrc), "");
        check("the texel-buffer emulation is not eligible",
                !MetalTexture.privateEligible(binding | copyDst), "");
        check("a sampled-only texture is not eligible", !MetalTexture.privateEligible(binding), "");
        check("a bare texture is not eligible", !MetalTexture.privateEligible(0), "");

        // The switch may only ever make storage more conservative. This must hold however the
        // process was launched, so it is asserted over every combination rather than one sample.
        boolean safe = true;
        String offender = "";
        int[] flags = {0, copyDst, copySrc, binding, render};
        for (int a : flags) {
            for (int b : flags) {
                int usage = a | b;
                if (!MetalTexture.privateEligible(usage) && !MetalTexture.usesSharedStorage(usage)) {
                    safe = false;
                    offender = "usage=" + usage;
                }
            }
        }
        check("a texture that is not eligible for private storage always stays shared", safe, offender);

        // With the feature on, a render attachment is the resource the change is for.
        boolean enabled = MetalTexture.privateTexturesEnabled();
        check("the render-attachment decision follows the switch",
                MetalTexture.usesSharedStorage(render) != enabled,
                "privateTextures=" + enabled);

        // The switch is off by default because private storage measured 9% slower, so an
        // unrecognised value has to fail safe rather than silently turning it on.
        check("the switch is off unless explicitly enabled",
                !MetalTexture.enablePrivateTextures("false")
                        && !MetalTexture.enablePrivateTextures("no")
                        && !MetalTexture.enablePrivateTextures("")
                        && !MetalTexture.enablePrivateTextures("ture"), "");
        check("the switch turns on for all and true",
                MetalTexture.enablePrivateTextures("all") && MetalTexture.enablePrivateTextures("true")
                        && MetalTexture.enablePrivateTextures("TRUE") && MetalTexture.enablePrivateTextures("All"),
                "");
        check("the default is off", !enabled, "privateTextures defaulted to on");

        System.out.println(failures == 0 ? "[TEST] storage mode rule OK" : "[TEST] " + failures + " FAILED");
        return failures;
    }

    private MetalTextureStorageTest() {
    }
}
