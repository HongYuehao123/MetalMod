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
        testSubBufferOffsetsAreAbsolute();

        return failures;
    }

    /**
     * BUG-009: a sub-buffer shares its parent's MTLBuffer handle, so a slice over it must be bound at
     * the sub-buffer's own base inside that handle. Before this was handled, every transient-arena
     * allocation bound the arena's start instead of its own data.
     */
    private static void testSubBufferOffsetsAreAbsolute() {
        MetalBuffer arena = new MetalBuffer(0, 4096L, MemorySegment.NULL, MemorySegment.NULL, false, null);
        check("a root buffer has base offset 0", arena.baseOffset() == 0L,
                "got " + arena.baseOffset());

        MetalBuffer first = MetalBuffer.sub(0, 256L, arena, 0L);
        MetalBuffer second = MetalBuffer.sub(0, 256L, arena, 256L);
        MetalBuffer third = MetalBuffer.sub(0, 256L, arena, 512L);

        check("second allocation sits at 256, not 0", second.baseOffset() == 256L,
                "got " + second.baseOffset());
        check("third allocation sits at 512", third.baseOffset() == 512L,
                "got " + third.baseOffset());
        check("a slice over the second allocation binds at 256",
                MetalRenderPassBackend.absoluteOffset(second.slice()) == 256L,
                "got " + MetalRenderPassBackend.absoluteOffset(second.slice()));
        MetalBuffer nested = MetalBuffer.sub(0, 64L, second, 64L);
        check("a nested sub-buffer accumulates its parent's base", nested.baseOffset() == 320L,
                "got " + nested.baseOffset());
        check("an offset inside a slice is added to the base",
                MetalRenderPassBackend.absoluteOffset(third.slice(64L, 64L)) == 576L,
                "got " + MetalRenderPassBackend.absoluteOffset(third.slice(64L, 64L)));
        check("the first allocation still binds at 0",
                MetalRenderPassBackend.absoluteOffset(first.slice()) == 0L,
                "got " + MetalRenderPassBackend.absoluteOffset(first.slice()));
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
