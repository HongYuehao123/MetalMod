package net.metalmod.backend;

import java.io.File;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Panama FFI bindings for the Metal substrate (metalmod_metal.h): device, queue, layer, texture,
 * buffer, sampler, clear, shader library, pipeline, render pass and blit.
 */
public final class MetalNative {

    private static boolean available = false;
    private static String loadError = null;

    private static MethodHandle mhDeviceCreate, mhDeviceRelease, mhDeviceInfo,
            mhDeviceMaxTextureSize, mhDeviceMaxBufferSize, mhDeviceRecommendedWorkingSet;
    private static MethodHandle mhQueueCreate, mhQueueRelease;
    private static MethodHandle mhLayerCreateForNsWindow, mhLayerRelease, mhLayerConfigure,
            mhLayerAcquire, mhLayerPresentClear, mhLayerPresentTexture, mhLayerSetPresentQueue;
    private static MethodHandle mhTextureCreateFull, mhTextureCreateView, mhTextureReplaceRegion,
            mhTextureReadRegion, mhTextureRelease;
    private static MethodHandle mhBufferCreate, mhBufferContents, mhBufferLength, mhBufferRelease;
    private static MethodHandle mhSamplerCreate, mhSamplerRelease, mhClearTextures;
    private static MethodHandle mhCommandBufferCreate, mhCommandBufferCommit, mhCommandBufferWait,
            mhCommandBufferRelease;
    private static MethodHandle mhLibraryCreate, mhLibraryRelease, mhRenderPipelineCreate,
            mhRenderPipelineRelease;
    private static MethodHandle mhRenderPassBegin, mhRenderPassEnd, mhRenderPassSetPipeline,
            mhRenderPassSetVertexBuffer, mhRenderPassSetFragmentBuffer, mhRenderPassSetVertexTexture,
            mhRenderPassSetFragmentTexture, mhRenderPassSetVertexSampler, mhRenderPassSetFragmentSampler,
            mhRenderPassSetScissor, mhRenderPassDraw, mhRenderPassDrawIndexed;

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
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("mac")) throw new UnsupportedOperationException("MetalMod requires macOS.");

        File devBuild = new File("native/build/libmetalmod.dylib");
        File dylib;
        if (devBuild.exists()) {
            dylib = devBuild;
        } else {
            InputStream in = MetalNative.class.getResourceAsStream("/natives/libmetalmod.dylib");
            if (in == null) in = MetalNative.class.getResourceAsStream("/libmetalmod.dylib");
            if (in == null) throw new IllegalStateException("Embedded libmetalmod.dylib not found");
            dylib = File.createTempFile("libmetalmod-", ".dylib");
            dylib.deleteOnExit();
            Files.copy(in, dylib.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        System.load(dylib.getAbsolutePath());

        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.loaderLookup();
        var A = ValueLayout.ADDRESS;
        var I = ValueLayout.JAVA_INT;
        var L = ValueLayout.JAVA_LONG;
        var B = ValueLayout.JAVA_BOOLEAN;
        var F = ValueLayout.JAVA_FLOAT;
        var D = ValueLayout.JAVA_DOUBLE;

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
        mhLayerAcquire = linker.downcallHandle(symbol(lookup, "mmm_layer_acquire"), FunctionDescriptor.of(I, A, A, A));
        mhLayerPresentClear = linker.downcallHandle(symbol(lookup, "mmm_layer_present_clear"), FunctionDescriptor.of(I, A, A, F, F, F, F));
        mhLayerPresentTexture = linker.downcallHandle(symbol(lookup, "mmm_layer_present_texture"), FunctionDescriptor.of(I, A, A, A));
        mhLayerSetPresentQueue = linker.downcallHandle(symbol(lookup, "mmm_layer_set_present_queue"), FunctionDescriptor.ofVoid(A));

        mhTextureCreateFull = linker.downcallHandle(symbol(lookup, "mmm_texture_create_full"),
                FunctionDescriptor.of(A, A, L, I, I, I, I, I, B, I));
        mhTextureCreateView = linker.downcallHandle(symbol(lookup, "mmm_texture_create_view"),
                FunctionDescriptor.of(A, A, L, I, I, I, I, I));
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
                FunctionDescriptor.of(A, A, I, I, I, I, I, B, D));
        mhSamplerRelease = linker.downcallHandle(symbol(lookup, "mmm_sampler_release"), FunctionDescriptor.ofVoid(A));
        mhClearTextures = linker.downcallHandle(symbol(lookup, "mmm_clear_textures"),
                FunctionDescriptor.of(I, A, A, B, F, F, F, F, A, B, D));

        mhCommandBufferCreate = linker.downcallHandle(symbol(lookup, "mmm_command_buffer_create"), FunctionDescriptor.of(A, A));
        mhCommandBufferCommit = linker.downcallHandle(symbol(lookup, "mmm_command_buffer_commit"), FunctionDescriptor.ofVoid(A));
        mhCommandBufferWait = linker.downcallHandle(symbol(lookup, "mmm_command_buffer_wait"), FunctionDescriptor.ofVoid(A));
        mhCommandBufferRelease = linker.downcallHandle(symbol(lookup, "mmm_command_buffer_release"), FunctionDescriptor.ofVoid(A));

        mhLibraryCreate = linker.downcallHandle(symbol(lookup, "mmm_library_create"), FunctionDescriptor.of(A, A, A, L));
        mhLibraryRelease = linker.downcallHandle(symbol(lookup, "mmm_library_release"), FunctionDescriptor.ofVoid(A));
        mhRenderPipelineCreate = linker.downcallHandle(symbol(lookup, "mmm_render_pipeline_create"),
                FunctionDescriptor.of(A, A, A, A, A, A, L, I, I, I, I, I, I, I, I, L, I, I, I, I, I, I, F, F, A, I, A, I));
        mhRenderPipelineRelease = linker.downcallHandle(symbol(lookup, "mmm_render_pipeline_release"), FunctionDescriptor.ofVoid(A));

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
        mhRenderPassDraw = linker.downcallHandle(symbol(lookup, "mmm_render_pass_draw"), FunctionDescriptor.ofVoid(A, I, I, I, I, I));
        mhRenderPassDrawIndexed = linker.downcallHandle(symbol(lookup, "mmm_render_pass_draw_indexed"), FunctionDescriptor.ofVoid(A, I, A, L, I, I, I, I, I, I));
    }

    private static MemorySegment symbol(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() -> new IllegalStateException(
                "libmetalmod.dylib does not export '" + name + "'. Rebuild the native library."));
    }

    public static boolean isAvailable() { return available; }
    public static String getLoadError() { return loadError; }

    private static MemorySegment addr(MethodHandle h, Object... a) {
        try { return (MemorySegment) h.invokeWithArguments(a); } catch (Throwable t) { throw new RuntimeException(t); }
    }
    private static int i(MethodHandle h, Object... a) {
        try { return (int) h.invokeWithArguments(a); } catch (Throwable t) { throw new RuntimeException(t); }
    }
    private static long l(MethodHandle h, Object... a) {
        try { return (long) h.invokeWithArguments(a); } catch (Throwable t) { throw new RuntimeException(t); }
    }
    private static void v(MethodHandle h, Object... a) {
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
    public static MemorySegment bufferCreate(MemorySegment dev, long length) { return addr(mhBufferCreate, dev, Math.max(1L, length)); }
    public static MemorySegment bufferContents(MemorySegment buf, long length) {
        MemorySegment p = addr(mhBufferContents, buf);
        return isNull(p) ? MemorySegment.NULL : p.reinterpret(Math.max(1L, length));
    }
    public static long bufferLength(MemorySegment buf) { return l(mhBufferLength, buf); }
    public static void bufferRelease(MemorySegment buf) { v(mhBufferRelease, buf); }
    public static MemorySegment samplerCreate(MemorySegment dev, int au, int av, int min, int mag, int aniso, boolean hasLod, double lod) {
        return addr(mhSamplerCreate, dev, au, av, min, mag, aniso, hasLod, lod);
    }
    public static void samplerRelease(MemorySegment s) { v(mhSamplerRelease, s); }
    public static int clearTextures(MemorySegment queue, MemorySegment color, boolean hasColor, float r, float g, float b, float a, MemorySegment depth, boolean hasDepth, double depthValue) {
        return i(mhClearTextures, queue, hasColor ? color : MemorySegment.NULL, hasColor, r, g, b, a,
                hasDepth ? depth : MemorySegment.NULL, hasDepth, depthValue);
    }

    // Command buffers ----------------------------------------------------------------------------

    public static MemorySegment commandBufferCreate(MemorySegment queue) { return addr(mhCommandBufferCreate, queue); }
    public static void commandBufferCommit(MemorySegment cb) { v(mhCommandBufferCommit, cb); }
    public static void commandBufferWait(MemorySegment cb) { v(mhCommandBufferWait, cb); }
    public static void commandBufferRelease(MemorySegment cb) { v(mhCommandBufferRelease, cb); }

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

    // Render pass --------------------------------------------------------------------------------

    public static MemorySegment renderPassBegin(MemorySegment cb, int colorCount, MemorySegment colorTextures,
            MemorySegment colorLoadClear, MemorySegment clearColors, MemorySegment depthTexture,
            boolean depthLoadClear, double depthValue, int width, int height) {
        return addr(mhRenderPassBegin, cb, colorCount, colorTextures, colorLoadClear, clearColors,
                depthTexture == null ? MemorySegment.NULL : depthTexture, depthLoadClear ? 1 : 0,
                depthValue, width, height);
    }
    public static void renderPassEnd(MemorySegment enc) { v(mhRenderPassEnd, enc); }
    public static void renderPassSetPipeline(MemorySegment enc, MemorySegment pipeline) { v(mhRenderPassSetPipeline, enc, pipeline); }
    public static void renderPassSetVertexBuffer(MemorySegment enc, MemorySegment buffer, long offset, int index) { v(mhRenderPassSetVertexBuffer, enc, buffer, offset, index); }
    public static void renderPassSetFragmentBuffer(MemorySegment enc, MemorySegment buffer, long offset, int index) { v(mhRenderPassSetFragmentBuffer, enc, buffer, offset, index); }
    public static void renderPassSetVertexTexture(MemorySegment enc, MemorySegment texture, int index) { v(mhRenderPassSetVertexTexture, enc, texture, index); }
    public static void renderPassSetFragmentTexture(MemorySegment enc, MemorySegment texture, int index) { v(mhRenderPassSetFragmentTexture, enc, texture, index); }
    public static void renderPassSetVertexSampler(MemorySegment enc, MemorySegment sampler, int index) { v(mhRenderPassSetVertexSampler, enc, sampler, index); }
    public static void renderPassSetFragmentSampler(MemorySegment enc, MemorySegment sampler, int index) { v(mhRenderPassSetFragmentSampler, enc, sampler, index); }
    public static void renderPassSetScissor(MemorySegment enc, int x, int y, int w, int h) { v(mhRenderPassSetScissor, enc, x, y, w, h); }
    public static void renderPassDraw(MemorySegment enc, int topology, int vertexStart, int vertexCount, int instanceCount, int firstInstance) {
        v(mhRenderPassDraw, enc, topology, vertexStart, vertexCount, instanceCount, firstInstance);
    }
    public static void renderPassDrawIndexed(MemorySegment enc, int topology, MemorySegment indexBuffer, long offset,
            int indexType, int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        v(mhRenderPassDrawIndexed, enc, topology, indexBuffer, offset, indexType, indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
    }
}
