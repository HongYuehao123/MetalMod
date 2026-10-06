package net.metalmod.backend;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;

/**
 * Panama FFI bindings for the Metal substrate (metalmod_metal.h): device, queue, layer, texture,
 * buffer, sampler, clear, shader library, pipeline, render pass and blit.
 */
public final class MetalNative {

    private static MethodHandle mhPipelineDepthVariant, mhPipelineReactiveVariant;
    private static MethodHandle mhFxSupported, mhFxCreate, mhFxRelease, mhFxEncode, mhFxHealthy, mhFxSetAntialias, mhFxGpuDuration;
    private static MethodHandle mhTemporalSupported, mhTemporalCreate, mhTemporalRelease, mhTemporalHealthy, mhTemporalEncode, mhTemporalTextureUsage;
    private static MethodHandle mhFgCreate, mhFgRelease, mhFgCapture, mhFgCaptureHand, mhFgCoverage, mhFgGuiCoverage, mhFgPresent, mhFgStats;
    private static MethodHandle mhTemporalFrameCreate, mhTemporalFrameRelease, mhTemporalFrameHealthy, mhTemporalFrameEncode, mhTemporalFrameDuration, mhTemporalFrameTexture;
    private static MethodHandle mhTemporalColorCreate, mhTemporalColorRelease, mhTemporalColorHealthy, mhTemporalColorEncode;

    private static MethodHandle mhInterpolationSupported, mhInterpolationCreate, mhInterpolationRelease,
            mhInterpolationHealthy, mhInterpolationUsage, mhInterpolationEncode;

    private static MethodHandle mhDisplayLinkCreate, mhDisplayLinkHealthy, mhDisplayLinkSubmit,
            mhDisplayLinkStop, mhDisplayLinkRelease, mhDisplayLinkStats, mhUtilityEndFrame;

    private static boolean available = false;
    private static String loadError = null;
    private static MethodHandle mhCaptureSetEnabled, mhCaptureReadReset;
    private static MethodHandle mhGpuProfileSetEnabled, mhGpuProfileReadReset;
    public static final java.util.List<String> GPU_PROFILE_METRICS = java.util.List.of(
            "gpu_completed_render_passes", "gpu_completed_vertex_ns", "gpu_completed_fragment_ns",
            "gpu_profile_dropped_passes", "gpu_profile_invalid_passes");
    private static boolean capturing;
    private static Thread captureThread;
    private static long capturePipelineNanos, capturePipelineCount;

    // Order is the ABI in MMMCaptureMetric (metalmod_metal.h). All *_ns values are CPU wall time.
    public static final java.util.List<String> CAPTURE_METRICS = java.util.List.of(
            "submissions", "command_buffer_create_ns", "commit_ns", "buffer_writes",
            "buffer_upload_bytes", "staging_allocations", "staging_alloc_ns", "buffer_copies",
            "buffer_copy_bytes", "texture_copies", "texture_uploads", "texture_upload_bytes",
            "clears", "fence_creates", "fence_wait_calls", "fence_wait_ns", "queue_wait_ns",
            "upload_api_ns", "copy_api_ns", "readback_api_ns", "render_passes", "draws",
            "drawable_wait_ns");

    private static MethodHandle mhDeviceCreate, mhDeviceRelease, mhDeviceInfo,
            mhDeviceMaxTextureSize, mhDeviceMaxBufferSize, mhDeviceRecommendedWorkingSet;
    private static MethodHandle mhQueueCreate, mhQueueRelease;
    private static MethodHandle mhLayerCreateForNsWindow, mhLayerRelease, mhLayerConfigure,
            mhLayerAcquire, mhLayerAcquireDisplay, mhLayerPresentClear, mhLayerPresentTexture, mhLayerSetPresentQueue;
    private static MethodHandle mhTextureCreateFull, mhTextureCreateView, mhTextureReplaceRegion,
            mhTextureReadRegion, mhTextureRelease, mhCopyTextureToTexture, mhCopyBufferToBuffer;
    private static MethodHandle mhBufferCreate, mhBufferContents, mhBufferLength, mhBufferRelease,
            mhWriteBufferBytes;
    private static MethodHandle mhSamplerCreate, mhSamplerRelease, mhClearTextures, mhClearTexturesRegion;
    private static MethodHandle mhFenceCreate, mhFenceWait, mhFenceRelease;
    private static MethodHandle mhCommandBufferBatchCreate, mhCommandBufferCreate, mhCommandBufferCommit, mhCommandBufferWait,
            mhCommandBufferRelease;
    private static MethodHandle mhDrawIndexedUniform;
    private static MethodHandle mhEnableBufferOffsets;
    private static MethodHandle mhUniformBytes;
    private static MethodHandle mhLibraryCreate, mhLibraryRelease, mhRenderPipelineCreate,
            mhRenderPipelineRelease, mhLastError;
    private static MethodHandle mhRenderPassBegin, mhRenderPassEnd, mhRenderPassSetPipeline,
            mhRenderPassSetVertexBuffer, mhRenderPassSetFragmentBuffer, mhRenderPassSetVertexTexture,
            mhRenderPassSetFragmentTexture, mhRenderPassSetVertexSampler, mhRenderPassSetFragmentSampler,
            mhRenderPassSetScissor, mhRenderPassSetViewport, mhRenderPassPushDebugGroup,
            mhRenderPassPopDebugGroup, mhRenderPassDraw, mhRenderPassDrawFan, mhRenderPassDrawIndexed;

    static {
        try {
            load();
            available = true;
            System.out.println("[MetalMod] Metal backend native library ready (mmm_* symbols resolved).");
        } catch (Throwable t) {
            loadError = t.getMessage();
            System.err.println("[MetalMod] Metal backend native library unavailable: " + loadError);
        }
    }

    private MetalNative() {
    }

    private static void load() throws Exception {
        net.metalmod.ffi.NativeLibrary.load();

        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.loaderLookup();
        var A = ValueLayout.ADDRESS;
        var I = ValueLayout.JAVA_INT;
        var L = ValueLayout.JAVA_LONG;
        var B = ValueLayout.JAVA_BOOLEAN;
        var F = ValueLayout.JAVA_FLOAT;
        var D = ValueLayout.JAVA_DOUBLE;

        // Optional diagnostics must not disable rendering when an older dylib is installed.
        mhCaptureSetEnabled = lookup.find("mmm_capture_set_enabled")
                .map(s -> linker.downcallHandle(s, FunctionDescriptor.ofVoid(B))).orElse(null);
        mhCaptureReadReset = lookup.find("mmm_capture_read_reset")
                .map(s -> linker.downcallHandle(s, FunctionDescriptor.of(I, A, I))).orElse(null);
        mhGpuProfileSetEnabled = lookup.find("mmm_gpu_profile_set_enabled")
                .map(s -> linker.downcallHandle(s, FunctionDescriptor.ofVoid(B))).orElse(null);
        mhGpuProfileReadReset = lookup.find("mmm_gpu_profile_read_reset")
                .map(s -> linker.downcallHandle(s, FunctionDescriptor.of(I, A, I))).orElse(null);

        mhDeviceCreate = linker.downcallHandle(symbol(lookup, "mmm_device_create"), FunctionDescriptor.of(A));
        mhDeviceRelease = linker.downcallHandle(symbol(lookup, "mmm_device_release"), FunctionDescriptor.ofVoid(A));
        mhDeviceInfo = linker.downcallHandle(symbol(lookup, "mmm_device_info"), FunctionDescriptor.of(I, A, A, L, A, L, A, L));
        mhDeviceMaxTextureSize = linker.downcallHandle(symbol(lookup, "mmm_device_max_texture_size"), FunctionDescriptor.of(L, A));
        mhDeviceMaxBufferSize = linker.downcallHandle(symbol(lookup, "mmm_device_max_buffer_size"), FunctionDescriptor.of(L, A));
        mhDeviceRecommendedWorkingSet = linker.downcallHandle(symbol(lookup, "mmm_device_recommended_working_set"), FunctionDescriptor.of(L, A));
        mhQueueCreate = linker.downcallHandle(symbol(lookup, "mmm_queue_create"), FunctionDescriptor.of(A, A));
        mhQueueRelease = linker.downcallHandle(symbol(lookup, "mmm_queue_release"), FunctionDescriptor.ofVoid(A));
        mhLayerCreateForNsWindow = linker.downcallHandle(symbol(lookup, "mmm_layer_create_for_ns_window"), FunctionDescriptor.of(A, A));
        mhLayerRelease = linker.downcallHandle(symbol(lookup, "mmm_layer_release"), FunctionDescriptor.ofVoid(A));
        mhLayerConfigure = linker.downcallHandle(symbol(lookup, "mmm_layer_configure"), FunctionDescriptor.of(I, A, I, I, B));
        mhLayerAcquireDisplay = linker.downcallHandle(symbol(lookup,"mmm_layer_acquire_display"),FunctionDescriptor.of(A,A));
        mhLayerAcquire = linker.downcallHandle(symbol(lookup, "mmm_layer_acquire"), FunctionDescriptor.of(I, A, A, A));
        mhLayerPresentClear = linker.downcallHandle(symbol(lookup, "mmm_layer_present_clear"), FunctionDescriptor.of(I, A, A, F, F, F, F));
        mhLayerPresentTexture = linker.downcallHandle(symbol(lookup, "mmm_layer_present_texture"), FunctionDescriptor.of(I, A, A, A));
        mhLayerSetPresentQueue = linker.downcallHandle(symbol(lookup, "mmm_layer_set_present_queue"), FunctionDescriptor.ofVoid(A));

        mhTextureCreateFull = linker.downcallHandle(symbol(lookup, "mmm_texture_create_full"),
                FunctionDescriptor.of(A, A, L, I, I, I, I, I, B, I));
        mhTextureCreateView = linker.downcallHandle(symbol(lookup, "mmm_texture_create_view"),
                FunctionDescriptor.of(A, A, L, I, I, I, I, I));
        mhCopyTextureToTexture = linker.downcallHandle(symbol(lookup, "mmm_copy_texture_to_texture"),
                FunctionDescriptor.of(I, A, A, I, I, I, I, A, I, I, I, I, I, I, I));
        mhCopyBufferToBuffer = linker.downcallHandle(symbol(lookup, "mmm_copy_buffer_to_buffer"),
                FunctionDescriptor.of(I, A, A, L, A, L, L));
        mhWriteBufferBytes = linker.downcallHandle(symbol(lookup, "mmm_write_buffer_bytes"),
                FunctionDescriptor.of(I, A, A, L, A, L));
        mhTextureReplaceRegion = linker.downcallHandle(symbol(lookup, "mmm_texture_replace_region"),
                FunctionDescriptor.of(I, A, I, I, I, I, I, I, A, L));
        mhTextureReadRegion = linker.downcallHandle(symbol(lookup, "mmm_texture_read_region"),
                FunctionDescriptor.of(I, A, I, I, I, I, I, I, A, L, L));
        mhTextureRelease = linker.downcallHandle(symbol(lookup, "mmm_texture_release"), FunctionDescriptor.ofVoid(A));
        mhBufferCreate = linker.downcallHandle(symbol(lookup, "mmm_buffer_create"), FunctionDescriptor.of(A, A, L));
        mhBufferContents = linker.downcallHandle(symbol(lookup, "mmm_buffer_contents"), FunctionDescriptor.of(A, A));
        mhBufferLength = linker.downcallHandle(symbol(lookup, "mmm_buffer_length"), FunctionDescriptor.of(L, A));
        mhBufferRelease = linker.downcallHandle(symbol(lookup, "mmm_buffer_release"), FunctionDescriptor.ofVoid(A));
        mhSamplerCreate = linker.downcallHandle(symbol(lookup, "mmm_sampler_create"),
                FunctionDescriptor.of(A, A, I, I, I, I, I, I, B, D));
        mhSamplerRelease = linker.downcallHandle(symbol(lookup, "mmm_sampler_release"), FunctionDescriptor.ofVoid(A));
        mhClearTextures = linker.downcallHandle(symbol(lookup, "mmm_clear_textures"),
                FunctionDescriptor.of(I, A, A, B, F, F, F, F, A, B, D));
        mhClearTexturesRegion = linker.downcallHandle(symbol(lookup, "mmm_clear_textures_region"),
                FunctionDescriptor.of(I, A, A, B, F, F, F, F, A, B, D, I, I, I, I));
        mhFenceCreate = linker.downcallHandle(symbol(lookup, "mmm_fence_create"), FunctionDescriptor.of(A, A));
        mhFenceWait = linker.downcallHandle(symbol(lookup, "mmm_fence_wait"), FunctionDescriptor.of(B, A, L));
        mhFenceRelease = linker.downcallHandle(symbol(lookup, "mmm_fence_release"), FunctionDescriptor.ofVoid(A));

        mhQueueSynchronize = linker.downcallHandle(symbol(lookup, "mmm_queue_synchronize"), FunctionDescriptor.ofVoid(A));
        // MetalFX is optional: missing symbols must not disable the native renderer.
        mhFxSupported = lookup.find("mmm_fx_spatial_supported")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(B, A))).orElse(null);
        mhFxCreate = lookup.find("mmm_fx_spatial_create")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(A, A, I, I, I, I, L))).orElse(null);
        mhFxRelease = lookup.find("mmm_fx_spatial_release")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.ofVoid(A))).orElse(null);
        mhFxEncode = lookup.find("mmm_fx_spatial_encode")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(I, A, A, A, A, B))).orElse(null);
        mhFxSetAntialias = lookup.find("mmm_fx_spatial_set_antialias")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.ofVoid(A, B))).orElse(null);
        mhFxGpuDuration = lookup.find("mmm_fx_spatial_gpu_duration_ns")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(L, A))).orElse(null);
        mhFxHealthy = lookup.find("mmm_fx_spatial_healthy")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(B, A))).orElse(null);
        mhPipelineReactiveVariant=lookup.find("mmm_render_pipeline_reactive_variant")
                .map(h->linker.downcallHandle(h,FunctionDescriptor.of(A,A,A,A,L))).orElse(null);
        mhUtilityEndFrame = lookup.find("mmm_utility_end_frame")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.ofVoid())).orElse(null);
        mhFgCreate = lookup.find("mmm_fg_create").map(h -> linker.downcallHandle(h, FunctionDescriptor.of(A,A,A,I,I,I,I,L))).orElse(null);
        mhFgRelease = lookup.find("mmm_fg_release").map(h -> linker.downcallHandle(h, FunctionDescriptor.ofVoid(A))).orElse(null);
        mhFgCapture = lookup.find("mmm_fg_capture").map(h -> linker.downcallHandle(h,
                FunctionDescriptor.of(I,A,A,A,A,A,A,A,I,L,F,F,F,F,F,F,B))).orElse(null);
        mhFgGuiCoverage=lookup.find("mmm_fg_gui_coverage").map(h->linker.downcallHandle(h,FunctionDescriptor.of(A,A))).orElse(null);
        mhFgCoverage=lookup.find("mmm_fg_coverage").map(h->linker.downcallHandle(h,FunctionDescriptor.of(A,A))).orElse(null);
        mhFgCaptureHand = lookup.find("mmm_fg_capture_hand").map(h -> linker.downcallHandle(h, FunctionDescriptor.of(I,A,A,A,A))).orElse(null);
        mhFgPresent = lookup.find("mmm_fg_present_paced").map(h -> linker.downcallHandle(h, FunctionDescriptor.of(I,A,A,A,A,F))).orElse(null);
        mhFgStats = lookup.find("mmm_fg_stats").map(h -> linker.downcallHandle(h, FunctionDescriptor.of(I,A,A,I))).orElse(null);
        mhDisplayLinkCreate = lookup.find("mmm_display_link_create")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(A, A, A, I, I))).orElse(null);
        mhDisplayLinkHealthy = lookup.find("mmm_display_link_healthy")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(B, A))).orElse(null);
        mhDisplayLinkSubmit = lookup.find("mmm_display_link_submit")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(I, A, A, A, A, L, F, F, F, F))).orElse(null);
        mhDisplayLinkStop = lookup.find("mmm_display_link_stop")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(I, A))).orElse(null);
        mhDisplayLinkRelease = lookup.find("mmm_display_link_release")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.ofVoid(A))).orElse(null);
        mhDisplayLinkStats = lookup.find("mmm_display_link_stats")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(I, A, A, I))).orElse(null);
        mhInterpolationSupported = lookup.find("mmm_fx_interpolation_supported")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(B, A))).orElse(null);
        mhInterpolationCreate = lookup.find("mmm_fx_interpolation_create")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(A, A, I, I, I, I))).orElse(null);
        mhInterpolationRelease = lookup.find("mmm_fx_interpolation_release")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.ofVoid(A))).orElse(null);
        mhInterpolationHealthy = lookup.find("mmm_fx_interpolation_healthy")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(B, A))).orElse(null);
        mhInterpolationUsage = lookup.find("mmm_fx_interpolation_texture_usage")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(L, A, I))).orElse(null);
        mhInterpolationEncode = lookup.find("mmm_fx_interpolation_encode")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(I,
                        A, A, A, A, A, A, A, A, L, L, F, F, F, F, F, F, B, B))).orElse(null);
        mhTemporalSupported = lookup.find("mmm_fx_temporal_supported")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(B, A))).orElse(null);
        mhTemporalCreate = lookup.find("mmm_fx_temporal_create")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(A, A, I, I, I, I))).orElse(null);
        mhTemporalRelease = lookup.find("mmm_fx_temporal_release")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.ofVoid(A))).orElse(null);
        mhTemporalHealthy = lookup.find("mmm_fx_temporal_healthy")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(B, A))).orElse(null);
        mhTemporalEncode = lookup.find("mmm_fx_temporal_encode")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(I, A, A, A, A, A, A, A, F, F, B, B))).orElse(null);
        mhTemporalTextureUsage = lookup.find("mmm_fx_temporal_texture_usage")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(L, A, I))).orElse(null);
        mhTemporalFrameCreate = lookup.find("mmm_fx_temporal_frame_create")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(A, A, I, I, I, I, L))).orElse(null);
        mhTemporalFrameRelease = lookup.find("mmm_fx_temporal_frame_release")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.ofVoid(A))).orElse(null);
        mhTemporalFrameHealthy = lookup.find("mmm_fx_temporal_frame_healthy")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(B, A))).orElse(null);
        mhTemporalFrameDuration = lookup.find("mmm_fx_temporal_frame_gpu_duration_ns")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(L, A))).orElse(null);
        mhTemporalFrameTexture = lookup.find("mmm_fx_temporal_frame_texture")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(A, A, I))).orElse(null);
        mhTemporalFrameEncode = lookup.find("mmm_fx_temporal_frame_encode")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(I, A, A, A, A, A, A, A, I, F, F, B))).orElse(null);
        mhTemporalColorCreate = lookup.find("mmm_fx_temporal_color_create")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(A, A, L))).orElse(null);
        mhTemporalColorRelease = lookup.find("mmm_fx_temporal_color_release")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.ofVoid(A))).orElse(null);
        mhTemporalColorHealthy = lookup.find("mmm_fx_temporal_color_healthy")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(B, A))).orElse(null);
        mhTemporalColorEncode = lookup.find("mmm_fx_temporal_color_encode")
                .map(h -> linker.downcallHandle(h, FunctionDescriptor.of(I, A, A, A, A, B))).orElse(null);
        mhCommandBufferBatchCreate = lookup.find("mmm_command_buffer_batch_create")
                .map(symbol -> linker.downcallHandle(symbol, FunctionDescriptor.of(A, A))).orElse(null);
        mhCommandBufferCreate = linker.downcallHandle(symbol(lookup, "mmm_command_buffer_create"), FunctionDescriptor.of(A, A));
        mhCommandBufferCommit = linker.downcallHandle(symbol(lookup, "mmm_command_buffer_commit"), FunctionDescriptor.ofVoid(A));
        mhCommandBufferWait = linker.downcallHandle(symbol(lookup, "mmm_command_buffer_wait"), FunctionDescriptor.ofVoid(A));
        mhCommandBufferRelease = linker.downcallHandle(symbol(lookup, "mmm_command_buffer_release"), FunctionDescriptor.ofVoid(A));

        mhLibraryCreate = linker.downcallHandle(symbol(lookup, "mmm_library_create"), FunctionDescriptor.of(A, A, A, L));
        mhLibraryRelease = linker.downcallHandle(symbol(lookup, "mmm_library_release"), FunctionDescriptor.ofVoid(A));
        mhRenderPipelineCreate = linker.downcallHandle(symbol(lookup, "mmm_render_pipeline_create"),
                FunctionDescriptor.of(A, A, A, A, A, A, L, I, I, I, I, I, I, I, I, L, I, I, I, I, I, I, F, F, A, I, A, I));
        mhPipelineDepthVariant = linker.downcallHandle(symbol(lookup, "mmm_render_pipeline_depth_variant"), FunctionDescriptor.of(A, A, L));
        mhRenderPipelineRelease = linker.downcallHandle(symbol(lookup, "mmm_render_pipeline_release"), FunctionDescriptor.ofVoid(A));
        // Optional: it is a diagnostic, so a stale dylib without it must not disable the backend.
        mhLastError = lookup.find("mmm_last_error")
                .map(s -> linker.downcallHandle(s, FunctionDescriptor.of(A)))
                .orElse(null);

        mhRenderPassBegin = linker.downcallHandle(symbol(lookup, "mmm_render_pass_begin"),
                FunctionDescriptor.of(A, A, I, A, A, A, A, I, D, I, I));
        mhRenderPassEnd = linker.downcallHandle(symbol(lookup, "mmm_render_pass_end"), FunctionDescriptor.ofVoid(A));
        mhRenderPassSetPipeline = linker.downcallHandle(symbol(lookup, "mmm_render_pass_set_pipeline"), FunctionDescriptor.ofVoid(A, A));
        mhRenderPassSetVertexBuffer = linker.downcallHandle(symbol(lookup, "mmm_render_pass_set_vertex_buffer"), FunctionDescriptor.ofVoid(A, A, L, I));
        mhRenderPassSetFragmentBuffer = linker.downcallHandle(symbol(lookup, "mmm_render_pass_set_fragment_buffer"), FunctionDescriptor.ofVoid(A, A, L, I));
        mhRenderPassSetVertexTexture = linker.downcallHandle(symbol(lookup, "mmm_render_pass_set_vertex_texture"), FunctionDescriptor.ofVoid(A, A, I));
        mhRenderPassSetFragmentTexture = linker.downcallHandle(symbol(lookup, "mmm_render_pass_set_fragment_texture"), FunctionDescriptor.ofVoid(A, A, I));
        mhRenderPassSetVertexSampler = linker.downcallHandle(symbol(lookup, "mmm_render_pass_set_vertex_sampler"), FunctionDescriptor.ofVoid(A, A, I));
        mhRenderPassSetFragmentSampler = linker.downcallHandle(symbol(lookup, "mmm_render_pass_set_fragment_sampler"), FunctionDescriptor.ofVoid(A, A, I));
        mhRenderPassSetScissor = linker.downcallHandle(symbol(lookup, "mmm_render_pass_set_scissor"), FunctionDescriptor.ofVoid(A, I, I, I, I));
        mhRenderPassSetViewport = linker.downcallHandle(symbol(lookup, "mmm_render_pass_set_viewport"), FunctionDescriptor.ofVoid(A, D, D, D, D));
        mhRenderPassPushDebugGroup = linker.downcallHandle(symbol(lookup, "mmm_render_pass_push_debug_group"), FunctionDescriptor.ofVoid(A, A));
        mhRenderPassPopDebugGroup = linker.downcallHandle(symbol(lookup, "mmm_render_pass_pop_debug_group"), FunctionDescriptor.ofVoid(A));
        mhRenderPassDraw = linker.downcallHandle(symbol(lookup, "mmm_render_pass_draw"), FunctionDescriptor.ofVoid(A, I, I, I, I, I));
        mhRenderPassDrawFan = linker.downcallHandle(symbol(lookup, "mmm_render_pass_draw_fan"), FunctionDescriptor.ofVoid(A, I, I, I, I));
        mhDrawIndexedUniform = lookup.find("mmm_render_pass_draw_indexed_uniform")
                .map(s -> linker.downcallHandle(s, FunctionDescriptor.ofVoid(A, I, A, L, I, I, I, I, I, I, A, L, I, I))).orElse(null);
        mhEnableBufferOffsets = lookup.find("mmm_render_pass_enable_buffer_offsets")
                .map(s -> linker.downcallHandle(s, FunctionDescriptor.ofVoid(A))).orElse(null);
        mhUniformBytes = lookup.find("mmm_render_pass_set_uniform_bytes")
                .map(s -> linker.downcallHandle(s, FunctionDescriptor.of(I, A, A, I, I, I))).orElse(null);
        mhRenderPassDrawIndexed = linker.downcallHandle(symbol(lookup, "mmm_render_pass_draw_indexed"), FunctionDescriptor.ofVoid(A, I, A, L, I, I, I, I, I, I));
    }

    private static MemorySegment symbol(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() -> new IllegalStateException(
                "libmetalmod.dylib does not export '" + name + "'. Rebuild the native library."));
    }

    public static boolean isAvailable() { return available; }
    public static String getLoadError() { return loadError; }

    public static boolean captureAvailable() {
        return available && mhCaptureSetEnabled != null && mhCaptureReadReset != null;
    }

    public static void captureSetEnabled(boolean enabled) {
        try {
            mhCaptureSetEnabled.invokeExact(enabled);
            if (mhGpuProfileSetEnabled != null) mhGpuProfileSetEnabled.invokeExact(
                    enabled && Boolean.getBoolean("metalmod.gpuStageTiming"));
            capturing = enabled;
            captureThread = enabled ? Thread.currentThread() : null;
            capturePipelineNanos = capturePipelineCount = 0;
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    /** Caller owns and reuses the destination; diagnostics downcalls are excluded from ffiCalls. */
    public static void captureReadReset(MemorySegment destination) {
        try {
            int count = (int) mhCaptureReadReset.invokeExact(destination, CAPTURE_METRICS.size());
            if (count != CAPTURE_METRICS.size()) throw new IllegalStateException("Capture ABI mismatch");
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static long ffiCallCount() { return ffiCalls; }
    /** Completed GPU stage workload since the previous read; excludes diagnostics from FFI counts. */
    public static void gpuProfileReadReset(MemorySegment destination) {
        destination.fill((byte) 0xff);
        if (mhGpuProfileReadReset == null || !Boolean.getBoolean("metalmod.gpuStageTiming")) return;
        try {
            int count = (int) mhGpuProfileReadReset.invokeExact(destination, GPU_PROFILE_METRICS.size());
            if (count != GPU_PROFILE_METRICS.size()) throw new IllegalStateException("GPU profile ABI mismatch");
        } catch (Throwable t) { throw ffiFailure(t); }
    }
    public static long capturePipelineNanos() { return capturePipelineNanos; }
    public static long capturePipelineCount() { return capturePipelineCount; }
    static long beginPipelineCapture() {
        return capturing && Thread.currentThread() == captureThread ? System.nanoTime() : 0;
    }
    static void endPipelineCapture(long started) {
        if (started != 0) {
            capturePipelineNanos += System.nanoTime() - started;
            capturePipelineCount++;
        }
    }

    private static MemorySegment addr(MethodHandle h, Object... a) {
        ffiCalls++;
        try { return (MemorySegment) h.invokeWithArguments(a); } catch (Throwable t) { throw new RuntimeException(t); }
    }
    private static int i(MethodHandle h, Object... a) {
        ffiCalls++;
        try { return (int) h.invokeWithArguments(a); } catch (Throwable t) { throw new RuntimeException(t); }
    }
    private static long l(MethodHandle h, Object... a) {
        ffiCalls++;
        try { return (long) h.invokeWithArguments(a); } catch (Throwable t) { throw new RuntimeException(t); }
    }
    private static void v(MethodHandle h, Object... a) {
        ffiCalls++;
        try { h.invokeWithArguments(a); } catch (Throwable t) { throw new RuntimeException(t); }
    }
    private static boolean isNull(MemorySegment s) { return s == null || s.address() == 0; }

    // Device / queue / surface -----------------------------------------------------------------

    public static MemorySegment deviceCreate() { return addr(mhDeviceCreate); }
    public static void deviceRelease(MemorySegment d) { v(mhDeviceRelease, d); }
    public static String[] deviceInfo(MemorySegment d) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment name = a.allocate(256), vendor = a.allocate(128), driver = a.allocate(128);
            int rc = i(mhDeviceInfo, d, name, 256L, vendor, 128L, driver, 128L);
            return rc != 0 ? new String[]{"Unknown Metal device", "Apple", "Metal (macOS)"}
                    : new String[]{name.getString(0), vendor.getString(0), driver.getString(0)};
        }
    }
    public static long deviceMaxTextureSize(MemorySegment d) { return l(mhDeviceMaxTextureSize, d); }
    public static long deviceMaxBufferSize(MemorySegment d) { return l(mhDeviceMaxBufferSize, d); }
    public static long deviceRecommendedWorkingSet(MemorySegment d) { return l(mhDeviceRecommendedWorkingSet, d); }
    public static MemorySegment queueCreate(MemorySegment d) { return addr(mhQueueCreate, d); }
    public static void queueRelease(MemorySegment q) { v(mhQueueRelease, q); }
    public static MemorySegment layerCreateForNsWindow(long nsWindow) {
        return addr(mhLayerCreateForNsWindow, nsWindow == 0 ? MemorySegment.NULL : MemorySegment.ofAddress(nsWindow));
    }
    public static void layerRelease(MemorySegment layer) { v(mhLayerRelease, layer); }
    public static int layerConfigure(MemorySegment layer, int w, int h, boolean vsync) { return i(mhLayerConfigure, layer, w, h, vsync); }
    /** Display-only reservation; the render owner has already closed the utility frame. */
    public static MemorySegment layerAcquireDisplay(MemorySegment layer) {return addr(mhLayerAcquireDisplay,layer);}
    public static MemorySegment[] layerAcquire(MemorySegment layer) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = a.allocate(ValueLayout.ADDRESS), t = a.allocate(ValueLayout.ADDRESS);
            if (i(mhLayerAcquire, layer, d, t) != 0) return new MemorySegment[]{MemorySegment.NULL, MemorySegment.NULL};
            return new MemorySegment[]{d.get(ValueLayout.ADDRESS, 0), t.get(ValueLayout.ADDRESS, 0)};
        }
    }
    public static int layerPresentClear(MemorySegment layer, MemorySegment drawable, float r, float g, float b, float a) {
        return i(mhLayerPresentClear, layer, drawable, r, g, b, a);
    }
    public static int layerPresentTexture(MemorySegment layer, MemorySegment drawable, MemorySegment source) {
        return i(mhLayerPresentTexture, layer, drawable, source == null ? MemorySegment.NULL : source);
    }
    public static void layerSetPresentQueue(MemorySegment queue) {
        v(mhLayerSetPresentQueue, queue);
    }

    // Textures / buffers / samplers --------------------------------------------------------------

    public static MemorySegment textureCreateFull(MemorySegment dev, long pf, int w, int h, int layers, int mips, int type, boolean shared, int usage) {
        return addr(mhTextureCreateFull, dev, pf, w, h, layers, mips, type, shared, usage);
    }
    public static MemorySegment textureCreateView(MemorySegment tex, long pf, int type, int baseMip, int mips, int baseLayer, int layers) {
        return addr(mhTextureCreateView, tex, pf, type, baseMip, mips, baseLayer, layers);
    }
    public static void textureRelease(MemorySegment t) { v(mhTextureRelease, t); }
    /**
     * Copy a rectangle between textures with a blit encoder, on the device queue and committed in
     * order. The only correct path for depth attachments, and the only one that lands in the frame
     * where the engine expects it.
     */
    public static int copyTextureToTexture(MemorySegment queue, MemorySegment source, int sourceSlice,
            int sourceLevel, int sourceX, int sourceY, MemorySegment target, int targetSlice,
            int targetLevel, int targetX, int targetY, int width, int height, int depth) {
        return i(mhCopyTextureToTexture, queue, source, sourceSlice, sourceLevel, sourceX, sourceY,
                target, targetSlice, targetLevel, targetX, targetY, width, height, depth);
    }

    /**
     * Copy a byte range between two buffers with a blit encoder, committed on the device queue.
     *
     * <p>Used by {@code CommandEncoder.copyToBuffer}. The engine frees and immediately reuses a mesh
     * region in its staging-to-uber-buffer upload, and the copy has to be ordered behind the previous
     * frame reads of that region; a CPU memcpy is not.
     */
    public static int copyBufferToBuffer(MemorySegment queue, MemorySegment source, long sourceOffset,
            MemorySegment target, long targetOffset, long length) {
        ffiCalls++;
        try {
            return (int) mhCopyBufferToBuffer.invokeExact(queue, source, sourceOffset, target,
                    targetOffset, length);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    /**
     * Write CPU bytes into a buffer with a blit encoder, committed on the device queue.
     *
     * <p>Used by {@code CommandEncoder.writeToBuffer}. The engine rewrites its per-frame uniform
     * buffers (Globals, lighting, projection) with no fence, so the write has to be ordered behind the
     * previous frame reads of the same buffer; a CPU memcpy is not.
     */
    public static int writeBufferBytes(MemorySegment queue, MemorySegment target, long targetOffset,
            MemorySegment bytes, long length) {
        ffiCalls++;
        try {
            return (int) mhWriteBufferBytes.invokeExact(queue, target, targetOffset, bytes, length);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static int textureReplaceRegion(MemorySegment tex, int mip, int slice, int x, int y, int w, int h, ByteBuffer data, long rowBytes) {
        MemorySegment seg = MemorySegment.ofBuffer(data.duplicate());
        if (seg.isNative()) return i(mhTextureReplaceRegion, tex, mip, slice, x, y, w, h, seg, rowBytes);
        try (Arena a = Arena.ofConfined()) {
            long size = seg.byteSize();
            MemorySegment copy = a.allocate(Math.max(1L, size));
            MemorySegment.copy(seg, 0L, copy, 0L, size);
            return i(mhTextureReplaceRegion, tex, mip, slice, x, y, w, h, copy, rowBytes);
        }
    }
    public static int textureReplaceRegionRaw(MemorySegment tex, int mip, int slice, int x, int y, int w, int h, MemorySegment data, long rowBytes) {
        return i(mhTextureReplaceRegion, tex, mip, slice, x, y, w, h, data, rowBytes);
    }
    public static int textureReadRegion(MemorySegment tex, int mip, int slice, int x, int y, int w, int h, MemorySegment out, long cap, long rowBytes) {
        return i(mhTextureReadRegion, tex, mip, slice, x, y, w, h, out, cap, rowBytes);
    }
    private static MethodHandle mhQueueSynchronize;
    public static MemorySegment fenceCreate(MemorySegment queue) { return addr(mhFenceCreate, queue); }
    public static boolean fenceWait(MemorySegment fence, long timeoutNanos) {
        ffiCalls++;
        try {
            return (boolean) mhFenceWait.invokeExact(fence, timeoutNanos);
        } catch (Throwable t) {
            return true;
        }
    }
    public static void fenceRelease(MemorySegment fence) { v(mhFenceRelease, fence); }
    public static void queueSynchronize(MemorySegment queue) { v(mhQueueSynchronize, queue); }
    public static MemorySegment bufferCreate(MemorySegment dev, long length) { return addr(mhBufferCreate, dev, Math.max(1L, length)); }
    public static MemorySegment bufferContents(MemorySegment buf, long length) {
        MemorySegment p = addr(mhBufferContents, buf);
        return isNull(p) ? MemorySegment.NULL : p.reinterpret(Math.max(1L, length));
    }
    public static long bufferLength(MemorySegment buf) { return l(mhBufferLength, buf); }
    public static void bufferRelease(MemorySegment buf) { v(mhBufferRelease, buf); }
    public static MemorySegment samplerCreate(MemorySegment dev, int au, int av, int min, int mag, int mip, int aniso, boolean hasLod, double lod) {
        return addr(mhSamplerCreate, dev, au, av, min, mag, mip, aniso, hasLod, lod);
    }
    public static void samplerRelease(MemorySegment s) { v(mhSamplerRelease, s); }
    public static int clearTextures(MemorySegment queue, MemorySegment color, boolean hasColor, float r, float g, float b, float a, MemorySegment depth, boolean hasDepth, double depthValue) {
        ffiCalls++;
        // Typed locals, for the reason given on renderPassBegin.
        MemorySegment colorArgument = hasColor ? color : MemorySegment.NULL;
        MemorySegment depthArgument = hasDepth ? depth : MemorySegment.NULL;
        try {
            return (int) mhClearTextures.invokeExact(queue, colorArgument, hasColor, r, g, b, a,
                    depthArgument, hasDepth, depthValue);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }
    public static int clearTexturesRegion(MemorySegment queue, MemorySegment color, boolean hasColor, float r, float g, float b, float a, MemorySegment depth, boolean hasDepth, double depthValue, int x, int y, int width, int height) {
        ffiCalls++;
        MemorySegment colorArgument = hasColor ? color : MemorySegment.NULL;
        MemorySegment depthArgument = hasDepth ? depth : MemorySegment.NULL;
        try {
            return (int) mhClearTexturesRegion.invokeExact(queue, colorArgument, hasColor, r, g, b, a,
                    depthArgument, hasDepth, depthValue, x, y, width, height);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    // Command buffers ----------------------------------------------------------------------------

    public static MemorySegment renderPipelineDepthVariant(MemorySegment pipeline, long format) {
        try { return (MemorySegment) mhPipelineDepthVariant.invokeExact(pipeline, format); }
        catch (Throwable t) { throw new RuntimeException("depth-compatible pipeline", t); }
    }

    public static boolean fxSupported(MemorySegment device) {
        if (mhFxSupported == null || mhFxCreate == null || mhFxRelease == null
                || mhFxEncode == null || mhFxHealthy == null) return false;
        try { return (boolean) mhFxSupported.invokeExact(device); }
        catch (Throwable t) { return false; }
    }

    public static MemorySegment fxCreate(MemorySegment device, int iw, int ih, int ow, int oh, long format) {
        if (mhFxCreate == null) return MemorySegment.NULL;
        try { return (MemorySegment) mhFxCreate.invokeExact(device, iw, ih, ow, oh, format); }
        catch (Throwable t) { return MemorySegment.NULL; }
    }

    public static void fxRelease(MemorySegment scaler) {
        if (mhFxRelease == null || scaler.address() == 0) return;
        try { mhFxRelease.invokeExact(scaler); }
        catch (Throwable t) { throw new RuntimeException("MetalFX release", t); }
    }

    /** Diagnostic A/B control; a missing optional symbol leaves older libraries unchanged. */
    public static void fxSetAntialias(MemorySegment scaler, boolean enabled) {
        if (mhFxSetAntialias == null) return;
        try { mhFxSetAntialias.invokeExact(scaler, enabled); }
        catch (Throwable t) { throw new RuntimeException("MetalFX anti-alias control", t); }
    }

    /** Latest completed scaler submission duration, not frame GPU time. Missing symbols return -1. */
    public static long fxGpuDuration(MemorySegment scaler) {
        if (mhFxGpuDuration == null || scaler.address() == 0) return -1;
        try { return (long) mhFxGpuDuration.invokeExact(scaler); }
        catch (Throwable t) { return -1; }
    }

    public static boolean fxHealthy(MemorySegment scaler) {
        if (mhFxHealthy == null || scaler.address() == 0) return false;
        try { return (boolean) mhFxHealthy.invokeExact(scaler); }
        catch (Throwable t) { return false; }
    }

    public static int fxEncode(MemorySegment scaler, MemorySegment cb, MemorySegment input,
                               MemorySegment output, boolean plain) {
        if (mhFxEncode == null) return -1;
        try { return (int) mhFxEncode.invokeExact(scaler, cb, input, output, plain); }
        catch (Throwable t) { return -1; }
    }

    /** Developer-only presenter; old libraries leave ordinary presentation available. */
    public static MemorySegment frameGenerationCreate(MemorySegment device,MemorySegment queue,int iw,int ih,int ow,int oh,long format) {
        if(mhFgCreate==null||mhFgCapture==null||mhFgCaptureHand==null||mhFgCoverage==null||mhFgGuiCoverage==null||mhFgPresent==null||mhFgRelease==null||mhFgStats==null)return MemorySegment.NULL;
        return addr(mhFgCreate,device,queue,iw,ih,ow,oh,format);
    }
    public static void frameGenerationRelease(MemorySegment h) { if(h.address()!=0)v(mhFgRelease,h); }
    public static int frameGenerationCapture(MemorySegment h,MemorySegment cb,MemorySegment world,MemorySegment scene,MemorySegment depth,
            MemorySegment matrices,MemorySegment objects,int count,long id,float dt,float near,float far,float fov,float jx,float jy,boolean reset) {
        return i(mhFgCapture,h,cb,world,scene,depth,matrices,objects,count,id,dt,near,far,fov,jx,jy,reset);
    }
    public static MemorySegment frameGenerationGuiCoverage(MemorySegment h){return addr(mhFgGuiCoverage,h);}
    public static MemorySegment frameGenerationCoverage(MemorySegment h){return addr(mhFgCoverage,h);}
    public static int frameGenerationCaptureHand(MemorySegment h,MemorySegment cb,MemorySegment depth,MemorySegment color) {
        return i(mhFgCaptureHand,h,cb,depth,color);
    }
    public static int frameGenerationPresent(MemorySegment h,MemorySegment layer,MemorySegment drawable,MemorySegment real,float interval) {
        return i(mhFgPresent,h,layer,drawable,real,interval);
    }
    public static long[] frameGenerationStats(MemorySegment h) {
        long[] values=new long[17];if(h.address()==0||mhFgStats==null)return values;
        try(var arena=Arena.ofConfined()) {
            var memory=arena.allocate(136,8);
            if(i(mhFgStats,h,memory,17)==0)for(int j=0;j<17;j++)values[j]=memory.getAtIndex(ValueLayout.JAVA_LONG,j);
        }
        return values;
    }

    public static MemorySegment displayLinkCreate(MemorySegment layer, MemorySegment queue, int w, int h) {
        if (mhDisplayLinkCreate == null || mhDisplayLinkHealthy == null || mhDisplayLinkSubmit == null
                || mhDisplayLinkStop == null || mhDisplayLinkRelease == null || mhDisplayLinkStats == null
                || mhUtilityEndFrame == null) return MemorySegment.NULL;
        try { return (MemorySegment)mhDisplayLinkCreate.invokeExact(layer, queue, w, h); }
        catch (Throwable error) { throw ffiFailure(error); }
    }
    public static boolean displayLinkHealthy(MemorySegment handle) {
        if (mhDisplayLinkHealthy == null) return false;
        try { return (boolean)mhDisplayLinkHealthy.invokeExact(handle); }
        catch (Throwable error) { throw ffiFailure(error); }
    }
    public static int displayLinkSubmit(MemorySegment handle, MemorySegment cb, MemorySegment real,
            MemorySegment generated, long renderedId, float r, float g, float b, float a) {
        if (mhDisplayLinkSubmit == null) return -1;
        try { return (int)mhDisplayLinkSubmit.invokeExact(handle, cb, real, generated, renderedId, r, g, b, a); }
        catch (Throwable error) { throw ffiFailure(error); }
    }
    public static int displayLinkStop(MemorySegment handle) {
        if (handle.address() == 0) return 0;
        if (mhDisplayLinkStop == null) return -1;
        try { return (int)mhDisplayLinkStop.invokeExact(handle); }
        catch (Throwable error) { throw ffiFailure(error); }
    }
    public static void displayLinkRelease(MemorySegment handle) {
        if (handle.address() == 0 || mhDisplayLinkRelease == null) return;
        try { mhDisplayLinkRelease.invokeExact(handle); }
        catch (Throwable error) { throw ffiFailure(error); }
    }
    public static long[] displayLinkStats(MemorySegment handle) {
        long[] values = new long[14];
        if (mhDisplayLinkStats == null || handle.address() == 0) return values;
        try (var arena = Arena.ofConfined()) {
            var buffer = arena.allocate(ValueLayout.JAVA_LONG, values.length);
            int rc = (int)mhDisplayLinkStats.invokeExact(handle, buffer, values.length);
            if (rc != 0) throw new IllegalStateException("Display-link statistics failed: " + rc);
            return buffer.toArray(ValueLayout.JAVA_LONG);
        } catch (Throwable error) { throw ffiFailure(error); }
    }
    /** A real render boundary even when the display-link owns drawable acquisition. */
    public static void utilityEndFrame() {
        if (mhUtilityEndFrame == null) throw new IllegalStateException("Missing utility frame boundary");
        try { mhUtilityEndFrame.invokeExact(); }
        catch (Throwable error) { throw ffiFailure(error); }
    }

    /** Independent Phase 7C capability; querying it does not enable generated presentation. */
    public static boolean interpolationSupported(MemorySegment device) {
        if (mhInterpolationSupported == null || mhInterpolationCreate == null || mhInterpolationRelease == null
                || mhInterpolationHealthy == null || mhInterpolationUsage == null || mhInterpolationEncode == null) return false;
        try { return (boolean)mhInterpolationSupported.invokeExact(device); }
        catch (Throwable error) { throw new RuntimeException("Interpolation capability", error); }
    }
    public static MemorySegment interpolationCreate(MemorySegment device, int iw, int ih, int ow, int oh) {
        if (mhInterpolationCreate == null) return MemorySegment.NULL;
        try { return (MemorySegment)mhInterpolationCreate.invokeExact(device, iw, ih, ow, oh); }
        catch (Throwable error) { throw new RuntimeException("Interpolation creation", error); }
    }
    public static void interpolationRelease(MemorySegment handle) {
        if (handle.address() == 0 || mhInterpolationRelease == null) return;
        try { mhInterpolationRelease.invokeExact(handle); }
        catch (Throwable error) { throw new RuntimeException("Interpolation release", error); }
    }
    public static boolean interpolationHealthy(MemorySegment handle) {
        if (mhInterpolationHealthy == null) return false;
        try { return (boolean)mhInterpolationHealthy.invokeExact(handle); }
        catch (Throwable error) { throw new RuntimeException("Interpolation health", error); }
    }
    public static long interpolationTextureUsage(MemorySegment handle, int role) {
        if (mhInterpolationUsage == null) return -1;
        try { return (long)mhInterpolationUsage.invokeExact(handle, role); }
        catch (Throwable error) { throw new RuntimeException("Interpolation usage", error); }
    }
    /** ABI v1: 1 primes/resets/warms history; only 0 permits generated display. */
    public static int interpolationEncode(MemorySegment handle, MemorySegment cb, MemorySegment current,
            MemorySegment previous, MemorySegment depth, MemorySegment motion, MemorySegment ui,
            MemorySegment output, long previousId, long currentId, float dt, float nearPlane,
            float farPlane, float fovDegrees, float jitterX, float jitterY, boolean reversed, boolean reset) {
        if (mhInterpolationEncode == null) return -1;
        try { return (int)mhInterpolationEncode.invokeExact(handle, cb, current, previous, depth, motion,
                ui, output, previousId, currentId, dt, nearPlane, farPlane, fovDegrees, jitterX, jitterY, reversed, reset); }
        catch (Throwable error) { throw new RuntimeException("Interpolation encode", error); }
    }

    /** Prototype capability; never selects temporal for the active spatial coordinator. */
    public static boolean temporalSupported(MemorySegment device) {
        if (mhTemporalSupported == null || mhTemporalCreate == null || mhTemporalRelease == null
                || mhTemporalHealthy == null || mhTemporalEncode == null || mhTemporalTextureUsage == null) return false;
        try { return (boolean) mhTemporalSupported.invokeExact(device); }
        catch (Throwable t) { return false; }
    }

    /** Fixed-format linear-light ABI v1; see docs/phase7/temporal-contract.md. */
    public static MemorySegment temporalCreate(MemorySegment device, int iw, int ih, int ow, int oh) {
        if (mhTemporalCreate == null) return MemorySegment.NULL;
        try { return (MemorySegment) mhTemporalCreate.invokeExact(device, iw, ih, ow, oh); }
        catch (Throwable t) { return MemorySegment.NULL; }
    }

    public static void temporalRelease(MemorySegment scaler) {
        if (mhTemporalRelease == null || scaler.address() == 0) return;
        try { mhTemporalRelease.invokeExact(scaler); }
        catch (Throwable t) { throw new RuntimeException("Temporal prototype release", t); }
    }

    public static boolean temporalHealthy(MemorySegment scaler) {
        if (mhTemporalHealthy == null || scaler.address() == 0) return false;
        try { return (boolean) mhTemporalHealthy.invokeExact(scaler); }
        catch (Throwable t) { return false; }
    }

    /** Required usage bits: colour/depth/motion/reactive/output roles 0..4, -1 if unavailable. */
    public static long temporalTextureUsage(MemorySegment scaler, int role) {
        if (mhTemporalTextureUsage == null) return -1;
        try { return (long) mhTemporalTextureUsage.invokeExact(scaler, role); }
        catch (Throwable t) { throw new RuntimeException("Temporal texture usage query", t); }
    }

    /** Motion points from current to previous unjittered scene pixels; jitter is in scene pixels. */
    public static int temporalEncode(MemorySegment scaler, MemorySegment cb, MemorySegment color,
                                     MemorySegment depth, MemorySegment motion, MemorySegment reactive,
                                     MemorySegment output, float jitterX, float jitterY,
                                     boolean depthReversed, boolean reset) {
        if (mhTemporalEncode == null) return -1;
        try { return (int) mhTemporalEncode.invokeExact(scaler, cb, color, depth, motion, reactive,
                output, jitterX, jitterY, depthReversed, reset); }
        catch (Throwable t) { throw new RuntimeException("Temporal prototype encode", t); }
    }

    public static MemorySegment renderPipelineReactiveVariant(MemorySegment pipeline,MemorySegment library,long depth) {
        if(mhPipelineReactiveVariant==null)return MemorySegment.NULL;
        try(var arena=Arena.ofConfined()) {
            return (MemorySegment)mhPipelineReactiveVariant.invokeExact(pipeline,library,arena.allocateFrom("main0"),depth);
        }catch(Throwable error){throw new RuntimeException("Temporal reactive pipeline",error);}
    }
    public static MemorySegment temporalFrameCreate(MemorySegment device, int iw, int ih, int ow, int oh, long format) {
        if (mhTemporalFrameCreate == null || mhTemporalFrameEncode == null || mhTemporalFrameRelease == null
                || mhTemporalFrameHealthy == null) return MemorySegment.NULL;
        try { return (MemorySegment)mhTemporalFrameCreate.invokeExact(device,iw,ih,ow,oh,format); }
        catch (Throwable error) { throw new RuntimeException("Temporal frame creation",error); }
    }
    public static void temporalFrameRelease(MemorySegment frame) {
        if (frame.address()==0 || mhTemporalFrameRelease==null) return;
        try { mhTemporalFrameRelease.invokeExact(frame); }
        catch (Throwable error) { throw new RuntimeException("Temporal frame release",error); }
    }
    public static boolean temporalFrameHealthy(MemorySegment frame) {
        if (frame.address()==0 || mhTemporalFrameHealthy==null) return false;
        try { return (boolean)mhTemporalFrameHealthy.invokeExact(frame); }
        catch (Throwable error) { return false; }
    }
    public static MemorySegment temporalFrameTexture(MemorySegment frame,int role) {
        if(frame.address()==0||mhTemporalFrameTexture==null)return MemorySegment.NULL;
        try{return (MemorySegment)mhTemporalFrameTexture.invokeExact(frame,role);}
        catch(Throwable error){return MemorySegment.NULL;}
    }
    public static long temporalFrameDuration(MemorySegment frame) {
        if (frame.address()==0 || mhTemporalFrameDuration==null) return -1;
        try { return (long)mhTemporalFrameDuration.invokeExact(frame); }
        catch (Throwable error) { return -1; }
    }
    public static int temporalFrameEncode(MemorySegment frame, MemorySegment cb, MemorySegment color, MemorySegment depth,
            MemorySegment output, MemorySegment matrices, MemorySegment objects, int count, float jx, float jy, boolean reset) {
        if (mhTemporalFrameEncode==null) return -1;
        try { return (int)mhTemporalFrameEncode.invokeExact(frame,cb,color,depth,output,matrices,objects,count,jx,jy,reset); }
        catch (Throwable error) { throw new RuntimeException("Temporal frame encoding",error); }
    }

    /** Reusable sRGB-transfer conversion for SDR RGBA8/BGRA8; no filtering or size changes. */
    public static MemorySegment temporalColorCreate(MemorySegment device, long sdrFormat) {
        if (mhTemporalColorCreate == null || mhTemporalColorRelease == null
                || mhTemporalColorHealthy == null || mhTemporalColorEncode == null) return MemorySegment.NULL;
        try { return (MemorySegment) mhTemporalColorCreate.invokeExact(device, sdrFormat); }
        catch (Throwable t) { return MemorySegment.NULL; }
    }
    public static void temporalColorRelease(MemorySegment converter) {
        if (mhTemporalColorRelease == null || converter.address() == 0) return;
        try { mhTemporalColorRelease.invokeExact(converter); }
        catch (Throwable t) { throw new RuntimeException("Temporal colour release", t); }
    }
    public static boolean temporalColorHealthy(MemorySegment converter) {
        if (mhTemporalColorHealthy == null || converter.address() == 0) return false;
        try { return (boolean) mhTemporalColorHealthy.invokeExact(converter); }
        catch (Throwable t) { return false; }
    }
    public static int temporalColorEncode(MemorySegment converter, MemorySegment cb,
                                         MemorySegment input, MemorySegment output, boolean toLinear) {
        if (mhTemporalColorEncode == null) return -1;
        try { return (int) mhTemporalColorEncode.invokeExact(converter, cb, input, output, toLinear); }
        catch (Throwable t) { throw new RuntimeException("Temporal colour encode", t); }
    }

    /** Physical submissions are batched by default; the property supplies a runtime A/B baseline. */
    public static boolean commandBatchingEnabled() {
        return mhCommandBufferBatchCreate != null
                && Boolean.parseBoolean(System.getProperty("metalmod.commandBatching", "true"));
    }

    /** Ordinary passes share a native queue batch; MetalFX retains explicit command buffers. */
    public static MemorySegment commandBufferBatchCreate(MemorySegment queue) {
        if (!commandBatchingEnabled())
            return commandBufferCreate(queue);
        ffiCalls++;
        try { return (MemorySegment) mhCommandBufferBatchCreate.invokeExact(queue); }
        catch (Throwable t) { throw ffiFailure(t); }
    }

    public static MemorySegment commandBufferCreate(MemorySegment queue) {
        ffiCalls++;
        try {
            return (MemorySegment) mhCommandBufferCreate.invokeExact(queue);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }
    public static void commandBufferCommit(MemorySegment cb) {
        ffiCalls++;
        try {
            mhCommandBufferCommit.invokeExact(cb);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }
    public static void commandBufferWait(MemorySegment cb) {
        ffiCalls++;
        try {
            mhCommandBufferWait.invokeExact(cb);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }
    public static void commandBufferRelease(MemorySegment cb) {
        ffiCalls++;
        try {
            mhCommandBufferRelease.invokeExact(cb);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    // Shader libraries and pipelines -------------------------------------------------------------

    public static MemorySegment libraryCreate(MemorySegment dev, String msl) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment text = a.allocateFrom(msl);
            return addr(mhLibraryCreate, dev, text, text.byteSize() - 1);
        }
    }
    public static void libraryRelease(MemorySegment lib) { v(mhLibraryRelease, lib); }

    public static MemorySegment renderPipelineCreate(MemorySegment dev,
            MemorySegment vlib, String vfn, MemorySegment flib, String ffn,
            long colorFormat, int writeMask, int blendEnabled,
            int srcColor, int dstColor, int opColor, int srcAlpha, int dstAlpha, int opAlpha,
            long depthFormat, int depthCompare, int depthWrite,
            int topology, int winding, int cullMode, int triangleFill,
            float biasScale, float biasClamp,
            MemorySegment buffers, int bufferCount, MemorySegment attributes, int attributeCount) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment vname = a.allocateFrom(vfn);
            MemorySegment fname = a.allocateFrom(ffn);
            return addr(mhRenderPipelineCreate, dev, vlib, vname, flib, fname,
                    colorFormat, writeMask, blendEnabled, srcColor, dstColor, opColor, srcAlpha, dstAlpha, opAlpha,
                    depthFormat, depthCompare, depthWrite, topology, winding, cullMode, triangleFill,
                    biasScale, biasClamp, buffers, bufferCount, attributes, attributeCount);
        }
    }
    public static void renderPipelineRelease(MemorySegment p) { v(mhRenderPipelineRelease, p); }

    /**
     * Metal's own message for the most recent shader-library or pipeline failure. Without this the
     * caller can only report "pipeline creation failed"; the reason (a shader compile error, or a
     * pipeline validation error such as an interface mismatch) lives in the native layer.
     */
    public static String lastError() {
        if (mhLastError == null) return "";
        try {
            MemorySegment p = addr(mhLastError);
            if (isNull(p)) return "";
            return p.reinterpret(1024).getString(0);
        } catch (Throwable t) {
            return "";
        }
    }

    // Render pass --------------------------------------------------------------------------------
    //
    // Everything below uses invokeExact rather than the varargs helpers above. Every draw goes
    // through several of these, and invokeWithArguments boxes each primitive into an Object[] and
    // runs the generic argument-conversion path, so at 6-18k draws a frame that was tens of
    // thousands of boxed calls. invokeExact needs the static argument types at the call site to
    // match the native descriptor exactly, which is why each body is written out rather than
    // sharing a helper.
    //
    // ffiCalls counts them so the F3 line can show whether this path is actually load-bearing,
    // instead of that being assumed.

    /**
     * Native calls issued since startup. Read the delta once per frame through
     * {@link #ffiCallCount()}.
     *
     * <p>Deliberately not volatile and not public: it is a plain counter incremented on whichever
     * thread makes the call and read on the render thread, so it is only meaningful when the calls
     * and the read are on that thread - which is the case for everything the renderer does. The
     * capture read/reset downcalls do not count themselves.
     */
    private static long ffiCalls;

    private static RuntimeException ffiFailure(Throwable t) {
        return new RuntimeException(t);
    }

    public static MemorySegment renderPassBegin(MemorySegment cb, int colorCount, MemorySegment colorTextures,
            MemorySegment colorLoadClear, MemorySegment clearColors, MemorySegment depthTexture,
            boolean depthLoadClear, double depthValue, int width, int height) {
        ffiCalls++;
        // Bound to a local first: invokeExact uses the *static* type of each argument, and a
        // reference conditional is a poly expression, so writing the null check inline here compiles
        // to an (Object,...) descriptor and throws WrongMethodTypeException on every call.
        MemorySegment depth = depthTexture == null ? MemorySegment.NULL : depthTexture;
        try {
            MemorySegment encoder = (MemorySegment) mhRenderPassBegin.invokeExact(cb, colorCount, colorTextures,
                    colorLoadClear, clearColors, depth, depthLoadClear ? 1 : 0, depthValue, width, height);
            if (encoder.address() != 0 && bufferOffsetsEnabled()) {
                ffiCalls++;
                mhEnableBufferOffsets.invokeExact(encoder);
            }
            return encoder;
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static void renderPassEnd(MemorySegment enc) {
        ffiCalls++;
        try {
            mhRenderPassEnd.invokeExact(enc);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static void renderPassSetPipeline(MemorySegment enc, MemorySegment pipeline) {
        ffiCalls++;
        try {
            mhRenderPassSetPipeline.invokeExact(enc, pipeline);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static void renderPassSetVertexBuffer(MemorySegment enc, MemorySegment buffer, long offset, int index) {
        ffiCalls++;
        try {
            mhRenderPassSetVertexBuffer.invokeExact(enc, buffer, offset, index);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    /** Native Metal copies the supplied storage before returning. Optional adapter API. */
    public static void renderPassSetUniformBytes(MemorySegment enc, MemorySegment bytes,
                                                  int length, int vertexSlot, int fragmentSlot) {
        if (mhUniformBytes == null) throw new IllegalStateException("Rebuild MetalMod for inline uniforms");
        ffiCalls++;
        try {
            int result = (int) mhUniformBytes.invokeExact(enc, bytes, length, vertexSlot, fragmentSlot);
            if (result != 0) throw new IllegalArgumentException("inline uniform rejected: " + result);
        } catch (Throwable t) { throw ffiFailure(t); }
    }

    public static void renderPassSetFragmentBuffer(MemorySegment enc, MemorySegment buffer, long offset, int index) {
        ffiCalls++;
        try {
            mhRenderPassSetFragmentBuffer.invokeExact(enc, buffer, offset, index);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static void renderPassSetVertexTexture(MemorySegment enc, MemorySegment texture, int index) {
        ffiCalls++;
        try {
            mhRenderPassSetVertexTexture.invokeExact(enc, texture, index);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static void renderPassSetFragmentTexture(MemorySegment enc, MemorySegment texture, int index) {
        ffiCalls++;
        try {
            mhRenderPassSetFragmentTexture.invokeExact(enc, texture, index);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static void renderPassSetVertexSampler(MemorySegment enc, MemorySegment sampler, int index) {
        ffiCalls++;
        try {
            mhRenderPassSetVertexSampler.invokeExact(enc, sampler, index);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static void renderPassSetFragmentSampler(MemorySegment enc, MemorySegment sampler, int index) {
        ffiCalls++;
        try {
            mhRenderPassSetFragmentSampler.invokeExact(enc, sampler, index);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static void renderPassSetScissor(MemorySegment enc, int x, int y, int w, int h) {
        ffiCalls++;
        try {
            mhRenderPassSetScissor.invokeExact(enc, x, y, w, h);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static void renderPassSetViewport(MemorySegment enc, double x, double y, double w, double h) {
        ffiCalls++;
        try {
            mhRenderPassSetViewport.invokeExact(enc, x, y, w, h);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static void renderPassPushDebugGroup(MemorySegment enc, MemorySegment label) {
        ffiCalls++;
        try {
            mhRenderPassPushDebugGroup.invokeExact(enc, label);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static void renderPassPopDebugGroup(MemorySegment enc) {
        ffiCalls++;
        try {
            mhRenderPassPopDebugGroup.invokeExact(enc);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static void renderPassDraw(MemorySegment enc, int topology, int vertexStart, int vertexCount, int instanceCount, int firstInstance) {
        ffiCalls++;
        try {
            mhRenderPassDraw.invokeExact(enc, topology, vertexStart, vertexCount, instanceCount, firstInstance);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    /**
     * Draw a triangle fan. Metal has no fan primitive, so the native side expands one into an
     * indexed triangle list from a cached index pattern; {@code vertexStart} becomes Metal's
     * {@code baseVertex} rather than an offset into the vertex buffer.
     */
    public static void renderPassDrawFan(MemorySegment enc, int vertexStart, int vertexCount, int instanceCount, int firstInstance) {
        ffiCalls++;
        try {
            mhRenderPassDrawFan.invokeExact(enc, vertexStart, vertexCount, instanceCount, firstInstance);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static boolean fusedDrawsEnabled() {
        return mhDrawIndexedUniform != null && Boolean.getBoolean("metalmod.fusedDraws");
    }

    public static boolean bufferOffsetsEnabled() {
        return mhEnableBufferOffsets != null && !"false".equalsIgnoreCase(
                System.getProperty("metalmod.bufferOffsets", "true"));
    }

    public static void renderPassDrawIndexedUniform(MemorySegment enc, int topology,
            MemorySegment indices, long indexOffset, int indexType, int count, int instances,
            int firstIndex, int baseVertex, int firstInstance, MemorySegment uniform, long offset,
            int vertexSlot, int fragmentSlot) {
        ffiCalls++;
        try {
            mhDrawIndexedUniform.invokeExact(enc, topology, indices, indexOffset, indexType, count, instances,
                    firstIndex, baseVertex, firstInstance, uniform, offset, vertexSlot, fragmentSlot);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }

    public static void renderPassDrawIndexed(MemorySegment enc, int topology, MemorySegment indexBuffer, long offset,
            int indexType, int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        ffiCalls++;
        try {
            mhRenderPassDrawIndexed.invokeExact(enc, topology, indexBuffer, offset, indexType, indexCount,
                    instanceCount, firstIndex, baseVertex, firstInstance);
        } catch (Throwable t) {
            throw ffiFailure(t);
        }
    }
}
