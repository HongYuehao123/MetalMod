package net.metalmod.backend;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderPass;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Regression tests for the multi-draw path (BUG-004).
 *
 * <p>Before this was fixed, {@code drawMultipleIndexed} ignored each draw's
 * {@code uniformUploaderConsumer}, so the per-draw uniforms never reached the shader. For vanilla
 * chunk terrain that payload is the {@code GpuBufferSlice[]} of section info, uploaded as the
 * {@code ChunkSection} block - which holds {@code ModelViewMat} and {@code ChunkPosition}. With it
 * unbound, terrain had no transform at all.
 *
 * <p>These tests exercise the dispatch logic directly, with no live encoder: the payload is opaque
 * to the backend, so what matters is that it is forwarded to the consumer and that the consumer's
 * uploads reach the uniform sink.
 */
public final class MetalRenderPassBackendTest {

    private static int failures;

    private MetalRenderPassBackendTest() {
    }

    public static int runTests() {
        failures = 0;
        System.out.println("--------------------------------------------------");
        System.out.println("Multi-draw upload tests (BUG-004)");
        System.out.println("--------------------------------------------------");

        testConsumerReceivesPayloadAndUploads();
        testDrawWithoutConsumerIsHarmless();
        testPerDrawIndexBufferAndTypeWin();
        testPerDrawIndexFallsBackToPassLevel();

        return failures;
    }

    /** The payload must reach the consumer, and the consumer's uploads must reach the sink. */
    private static void testConsumerReceivesPayloadAndUploads() {
        List<String> uploaded = new ArrayList<>();
        List<String> payloadsSeen = new ArrayList<>();

        RenderPass.Draw<String> draw = new RenderPass.Draw<>(0, null, null, null, 0, 6, 0,
                (payload, uploader) -> {
                    payloadsSeen.add(payload);
                    uploader.upload("ChunkSection", (com.mojang.blaze3d.buffers.GpuBufferSlice) null);
                });

        MetalRenderPassBackend.uploadDrawUniforms(draw, "section-info",
                (name, slice) -> uploaded.add(name));

        check("consumer received the draw's payload",
                payloadsSeen.equals(List.of("section-info")), "saw=" + payloadsSeen);
        check("consumer upload reached the uniform sink",
                uploaded.equals(List.of("ChunkSection")), "uploaded=" + uploaded);
    }

    /** A draw with no consumer must be a no-op, not a crash. */
    private static void testDrawWithoutConsumerIsHarmless() {
        RenderPass.Draw<String> draw = new RenderPass.Draw<>(0, null, null, null, 0, 6, 0);
        List<String> uploaded = new ArrayList<>();
        try {
            MetalRenderPassBackend.uploadDrawUniforms(draw, "ignored",
                    (name, slice) -> uploaded.add(name));
            check("draw without a consumer is a no-op", uploaded.isEmpty(), "uploaded=" + uploaded);
        } catch (Throwable t) {
            check("draw without a consumer is a no-op", false, "threw " + t);
        }
    }

    /** A draw carrying its own index buffer/type must override the pass-level ones. */
    private static void testPerDrawIndexBufferAndTypeWin() {
        GpuBuffer own = new MetalBuffer(0, 16L, MemorySegment.NULL, MemorySegment.NULL, false, null);
        RenderPass.Draw<String> draw = new RenderPass.Draw<>(0, null, own, IndexType.INT, 0, 6, 0);

        check("per-draw index buffer overrides the pass-level one",
                MetalRenderPassBackend.effectiveIndexBuffer(draw, null) == own, "");
        check("per-draw index type overrides the pass-level one",
                MetalRenderPassBackend.effectiveIndexType(draw, IndexType.SHORT) == IndexType.INT,
                "got=" + MetalRenderPassBackend.effectiveIndexType(draw, IndexType.SHORT));
    }

    /** When a draw carries neither, the pass-level values apply. */
    private static void testPerDrawIndexFallsBackToPassLevel() {
        GpuBuffer fallback = new MetalBuffer(0, 16L, MemorySegment.NULL, MemorySegment.NULL, false, null);
        RenderPass.Draw<String> draw = new RenderPass.Draw<>(0, null, null, null, 0, 6, 0);

        check("index buffer falls back to the pass-level one",
                MetalRenderPassBackend.effectiveIndexBuffer(draw, fallback) == fallback, "");
        check("index type falls back to the pass-level one",
                MetalRenderPassBackend.effectiveIndexType(draw, IndexType.SHORT) == IndexType.SHORT,
                "got=" + MetalRenderPassBackend.effectiveIndexType(draw, IndexType.SHORT));
    }

    private static void check(String label, boolean condition, String detail) {
        if (condition) {
            System.out.println("PASS" + (detail.isEmpty() ? "" : " " + detail));
        } else {
            failures++;
            System.out.println("FAIL (" + label + ")" + (detail.isEmpty() ? "" : " " + detail));
        }
    }
}
