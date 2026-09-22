# Phase 1 boot-sequence trace (Step 1 calibration)

Generated from the real `MetalMod_Test_26.2.jar` (Minecraft 26.2, named mappings) with
`javap -c -p`. This document calibrates **Phase 1** of `ROADMAP.md`: it records the exact path
Minecraft takes to select a backend, create a device and reach the first presented frame, and what
that implies for the first implementation slice.

Method: all 10,952 classes were extracted and scanned for call sites; the classes on the boot path
were disassembled (`Minecraft`, `RenderSystem`, `Window`, `GpuDevice`, `GpuSurface`,
`SamplerCache`, `DynamicUniforms`, `DynamicUniformStorage`, `MappableRingBuffer`,
`RenderTarget`, `ShaderManager`, `GlBackend`, `VulkanBackend`, `GlDevice`).

---

## 1. Backend selection (`net.minecraft.client.Minecraft` constructor)

Bytecode offsets in the constructor that performs startup:

1. `options.preferredGraphicsBackend` selects a `PreferredGraphicsApi` (with a crash-recovery
   override that forces OPENGL/DEFAULT after an unexpected shutdown).
2. `PreferredGraphicsApi.getBackendsToTry()` (offset 967) returns `GpuBackend[]`.
   - Verified body: `VULKAN` -> `[VulkanBackend, GlBackend]`; any other value ->
     `[GlBackend, VulkanBackend]`. **There is no Metal entry**; a mixin must prepend one.
3. For each backend, in order:
   - `GLFW.glfwDefaultWindowHints()`, then set `GLFW_VISIBLE` (0x20010).
   - `new Window(...backend)` (offset 1068). The `Window` constructor calls
     `backend.setWindowHints()` (Window offset 22), creates the GLFW window, then calls
     `backend.handleWindowCreationErrors(error)` (Window offset 58).
   - `backend.createDevice(window.handle(), shaderSource, debugOptions, criticalShaderLoader)`
     (offset 1126). `window.handle()` is the raw `GLFWwindow*`.
   - `device.getDeviceInfo()` (offset 1135).
   - `GLFW.glfwSetWindowSizeLimits(...)` using `DeviceLimits.maxTextureSizeForFormat(RGBA8_UNORM)`.
   - `RenderSystem.initRenderer(device)` (offset 1169).
   - Logs the device info and **breaks** the loop.
   - On `BackendCreationException`: logs `"Failed to create backend <name>"`, closes the window,
     and **continues with the next backend** (offset 1239 onward).
4. After the loop: `device.createSurface(window.handle())` (offset 1380) is stored as
   `windowSurface`.

**Consequence (important):** if `MetalBackend.createDevice` or `handleWindowCreationErrors`
throws `BackendCreationException`, Minecraft cleanly falls through to Vulkan/OpenGL. A broken
Metal backend cannot brick the game, which makes incremental development safe.

## 2. Exact backend calls made during device init

These all happen before the first frame, on the render thread:

| Call | Where | Notes |
|---|---|---|
| `GpuBackend.setWindowHints()` | `Window` ctor | Vulkan sets `GLFW_CLIENT_API` (139265) to `GLFW_NO_API` (0); Metal must do the same or GLFW creates a GL context |
| `GpuBackend.handleWindowCreationErrors(Error)` | `Window` ctor | Vulkan throws `BackendCreationException` on any GLFW error |
| `GpuBackend.createDevice(long, ShaderSource, GpuDebugOptions, Runnable)` | `Minecraft` | Must return a `GpuDevice` wrapping a `GpuDeviceBackend` |
| `GpuDeviceBackend.getDeviceInfo()` | `Minecraft`, `DynamicUniformStorage`, `RenderTarget` | Called several times |
| `GpuDeviceBackend.createSampler(...)` **x32** | `RenderSystem.initRenderer` -> `SamplerCache.initialize()` | 2 address modes x 2 x 2 filter modes x boolean = 32 |
| `GpuDeviceBackend.createBuffer(Supplier,int,long)` **x2** | `DynamicUniforms` -> `DynamicUniformStorage` -> `MappableRingBuffer` | Transform + chunk-section ring buffers; requires `USAGE_MAP_WRITE` |
| `GpuDeviceBackend.createSurface(long)` | `Minecraft` after the loop | `long` is `GLFWwindow*` |

`RenderSystem.initRenderer` itself is tiny: it stores `DEVICE`, constructs `DynamicUniforms`,
and calls `SamplerCache.initialize()`. The sampler and buffer creation is nested under those.

## 3. The per-frame path (`Minecraft.renderFrame(boolean)`)

1. If `windowSurface.isAcquired()`, return.
2. `Window.updateFullscreenIfChanged()`.
3. If the surface needs reconfiguring or is suboptimal:
   `glfwGetFramebufferSize`, `surface.supportedPresentModes()`,
   `GpuSurface.PresentMode.getSupportedVsyncMode(...)`, `surface.configure(config)`.
4. Unless the surface is invalid or the window is minimized: `surface.acquireNextTexture()`.
5. `Gui.update()`, `GameRenderer.update()`, `GameRenderer.extract(...)`.
6. `RenderSystem.executePendingTasks()`.
7. `GameRenderer.render(deltaTracker, renderLevel)` — the full renderer.
8. Present phase: `GameRenderer.mainRenderTarget().getColorTextureView()`,
   `device.createCommandEncoder()`, `surface.blitFromTexture(encoder, colorView)`.
9. `device.createCommandEncoder().submit()`.
10. `surface.present()`.
11. `DynamicUniforms.reset()`, `LevelRenderer.endFrame()`.

`GpuSurface` enforces its own state machine before reaching the backend:

- `blitFromTexture`: source must have `USAGE_COPY_SRC` (bit 2) and a single layer/depth; it is
  illegal inside a render pass.
- `present`: requires a prior blit this frame.
- `acquireNextTexture`: requires a prior `configure`.

## 4. Critical finding: a clear-only backend cannot boot

`ShaderManager.apply` iterates every `RenderPipeline` and calls
`device.precompilePipeline(pipeline, shaderSource)`. If **any** returned
`CompiledRenderPipeline.isValid()` is false, it collects the failures, calls
`clearPipelineCache()` + `loadCriticalShaders()`, then **throws a `RuntimeException` listing
the failed pipelines**. A backend that only knows how to clear and present will therefore crash
during resource reload, before the first frame.

`RenderTarget.createBuffers` additionally creates real GPU objects during renderer init:

- depth texture `D32_FLOAT`, usage `15` = `COPY_DST|COPY_SRC|TEXTURE_BINDING|RENDER_ATTACHMENT`,
- colour texture in the target's `GpuFormat`, same usage,
- a `GpuTextureView` for each.

**Therefore the roadmap's "minimal clear-only encoder, with the vanilla renderer disabled" is not
achievable through graceful degradation.** The engine drives the resource and pipeline path from
the first frame. The first slice must implement the resource types and return *valid* pipelines.

## 5. Revised Phase 1 scope: walking skeleton with a valid-but-inert pipeline layer

The smallest thing that reaches a presented frame is **not** a clear-only backend; it is a backend
that:

- implements real Metal device/queue/surface and real texture/buffer/sampler objects, plus clear and
  blit — so the engine's own render target and surface work;
- satisfies the boot-time validity checks: `precompilePipeline` returns a placeholder pipeline
  whose `isValid()` is `true`, and render passes accept draws but encode nothing.

The engine then clears its render target, blits it to the drawable, and presents — a cleared window,
which is exactly Phase 1's success criterion.

### Java deliverables

| Class | Interface | Member count |
|---|---|---|
| `MetalBackend` | `GpuBackend` | 4 |
| `MetalDeviceBackend` | `GpuDeviceBackend` | 15 |
| `MetalCommandEncoderBackend` | `CommandEncoderBackend` | 18 |
| `MetalRenderPassBackend` | `RenderPassBackend` | 22 |
| `MetalSurfaceBackend` | `GpuSurfaceBackend` | 7 |
| `MetalTransientMemory` | `TransientMemory` | 8 abstract |

Plus the resource/value types: `MetalTexture`, `MetalTextureView`, `MetalBuffer`,
`MetalSampler`, `MetalFence`, `MetalQueryPool`, `MetalCompiledPipeline`, and a
`DeviceInfo` builder (`DeviceLimits`, `DeviceFeatures`, `HintsAndWorkarounds`, `DeviceType`).

Draw/state methods in `MetalRenderPassBackend` start as no-ops in the skeleton; the mandatory real
ones are the clears, `blitFromTexture`, and the surface lifecycle.

### Native deliverables (`native/src/metalmod_metal.mm`)

- **Window/layer handshake (top risk).** In Java, `org.lwjgl.glfw.GLFWNativeCocoa.glfwGetCocoaWindow(long)`
  (confirmed present in `lwjgl-glfw-3.4.1.jar`) turns the `GLFWwindow*` into an `NSWindow*`.
  Pass that to a new native entry point that takes `[nsWindow contentView]`, attaches or adopts a
  `CAMetalLayer`, and configures its drawable size. The existing `mmm_layer_create(nsView)`
  already handles the NSView case; it needs the `NSWindow` entry point and resize handling.
- `GpuFormat` -> `MTLPixelFormat` mapping (colour, sRGB, `D32_FLOAT`, integer formats).
- Texture creation/usage mapping, texture views, buffers, samplers, clear passes (mostly present),
  and a texture -> drawable blit (new).

### Mixin

`PreferredGraphicsApiMixin` on `getBackendsToTry()` to prepend `MetalBackend` while preserving
the vanilla array order as the fallback.

## 6. Open risks

1. **Window/layer ownership.** GLFW owns the `NSWindow`; MetalMod attaches its own
   `CAMetalLayer` to the content view. Resize, fullscreen transitions and the content scale must be
   driven from the same place Minecraft already handles them (`Window` callbacks).
2. **Window hints.** Without `GLFW_CLIENT_API = GLFW_NO_API`, GLFW creates a GL context and the
   window is not usable for a Metal layer.
3. **DeviceInfo honesty.** `DeviceLimits`/`DeviceFeatures`/`HintsAndWorkarounds` steer engine
   code paths. Start conservative and enable features only once the corresponding path works.
4. **Threading.** Device init and every frame run on the render thread. The existing
   `MetalMod-Telemetry` thread must never touch the new backend.
5. **Pipeline placeholder.** `isValid() = true` hides compilation failures; log every placeholder
   pipeline so a later phase can replace them with real compilation.

## 7. Phase 1 milestones — achieved

- **1a — backend selection. ✅** Minecraft logged `Using graphics backend Metal, using drivers:
  Metal (macOS)`, created the `MTLDevice` and attached a `CAMetalLayer` to the GLFW NSWindow
  content view. Fallback is structural: a `BackendCreationException` returns control to the
  vanilla backend array.
- **1b — presented frame. ✅** The surface reported `first drawable acquired (1708x960)` and
  `presented 1 / 600 / 1200 / 1800 / 2400 frame(s) on Metal` (~60 fps) before a clean shutdown.
  The window shows a pulsing first-light clear colour because every draw is a no-op; textures,
  buffers, samplers and pipelines are placeholders until Phase 2/3.
