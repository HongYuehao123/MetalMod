# MetalMod: Apple Silicon Metal 4 & MetalFX Mod for Minecraft 26.2 / 26.3

MetalMod brings **Apple Silicon native graphics acceleration** to Minecraft 26.2 ("Chaos Cubed") and 26.3 ("Wilderness Bound") on macOS 26+.

Leveraging Minecraft's new **Vulkan backend** and Apple's **Metal 4** framework, MetalMod integrates hardware-accelerated **MetalFX Spatial Upscaling**, **MetalFX Temporal Upscaling**, and **MetalFX Frame Interpolation (Frame Generation)** with true zero-copy performance.

---

## Key Features

1. **Hardware MetalFX Upscaling**:
   - **MetalFX Spatial**: Low-latency, high-performance spatial upscaling.
   - **MetalFX Temporal**: Advanced temporal anti-aliasing and detail reconstruction powered by Apple Neural Engine and GPU compute.
   - **Quality Presets**: Native (100%), Ultra Quality (77%), Quality (67%), Balanced (58%), Performance (50%), Ultra Performance (33%).

2. **Metal 4 Native Frame Generation (`MTLFXFrameInterpolator`)**:
   - Hardware frame interpolation generating intermediate frames ($F_{t-0.5}$) in real-time.
   - Doubles presentation frame rate (e.g. 60 FPS internal -> 120 FPS display) to saturate Apple ProMotion (120Hz) displays smoothly.

3. **Zero-Copy Vulkan <-> Metal Bridge**:
   - On macOS, Minecraft runs on **MoltenVK** (`libMoltenVK.dylib`).
   - Uses the standard **`VK_EXT_metal_objects`** extension (`vkExportMetalObjectsEXT`) to retrieve native `id<MTLTexture>` handles directly from Minecraft's Vulkan framebuffers in unified memory ($0\text{ms}$ memory copy overhead).

4. **Decoupled Native UI Compositor**:
   - 3D world is rendered at lower resolution, upscaled, and frame-interpolated.
   - 2D GUI, HUD, crosshair, chat, hotbar, and inventory are rendered at **100% native resolution**.
   - Composited via a lightweight Metal alpha-blend pass immediately before presentation, ensuring pixel-perfect text without blur or motion artifacts.

5. **Subpixel Camera Jittering**:
   - Low-discrepancy Halton(2, 3) sequence injected into Minecraft's perspective projection matrix for temporal reconstruction.

6. **Modern Panama FFI Architecture**:
   - Java 22+ Foreign Function & Memory API eliminates JNI wrapper overhead and simplifies native calls.

---

## Architecture Diagram

```
Minecraft 26.2 / 26.3 (Vulkan Backend)
  │
  ├── 3D World Pass (Low-Res VkImage + Halton Jitter + Motion Vectors)
  │       │
  │       ▼
  │   MoltenVK (VK_EXT_metal_objects) ──► Zero-Copy id<MTLTexture>
  │                                              │
  │                                              ▼
  │                                      MetalFX Scaler (Spatial / Temporal)
  │                                              │
  │                                              ▼
  │                                      MetalFX Frame Interpolator (Metal 4)
  │                                              │
  ├── 2D UI / HUD Pass (Native-Res VkImage) ─────┼──► Metal Alpha Blend Compositor
                                                 │
                                                 ▼
                                        CAMetalDisplayLink (120Hz ProMotion)
                                                 │
                                                 ▼
                                         Apple Retina Display
```

---

## Directory Structure

```
MetalMod/
├── native/                         # Native Objective-C++ / Metal 4 library
│   ├── CMakeLists.txt              # CMake build targeting arm64 macOS 26+
│   ├── include/
│   │   ├── metalmod/
│   │   │   ├── metalmod.h          # Exported C API
│   │   │   └── metalmod_types.h    # Config, frame params, telemetry structs
│   │   └── vulkan/
│   │       ├── vulkan_core.h       # Minimal Vulkan handle definitions
│   │       └── vulkan_metal.h      # VK_EXT_metal_objects extension definitions
│   └── src/
│       ├── metalmod_internal.h     # State & category declarations
│       ├── metalmod_bridge.mm      # C API implementation & MoltenVK export
│       ├── metalmod_spatial.mm     # MTLFXSpatialScaler setup & encode
│       ├── metalmod_temporal.mm    # MTLFXTemporalScaler setup & encode
│       ├── metalmod_interpolator.mm# MTLFXFrameInterpolator setup & encode
│       ├── metalmod_compositor.mm  # Metal Shading Language UI composite pipeline
│       ├── metalmod_pacer.mm       # Pacing & presentation dispatch loop
│       └── shaders/
│           └── compositor.metal    # Metal shaders for UI blending
├── src/main/java/net/metalmod/
│   ├── client/
│   │   ├── MetalModClient.java     # Fabric ClientModInitializer & keybindings
│   │   └── gui/
│   │       └── MetalModConfigScreen.java # In-game configuration GUI
│   ├── config/
│   │   └── MetalConfig.java        # Configuration presets & disk persistence
│   ├── ffi/
│   │   └── MetalBridge.java        # Java Panama FFI bindings to libmetalmod.dylib
│   ├── mixin/
│   │   ├── GameRendererMixin.java  # Halton projection matrix jitter mixin
│   │   └── WindowMixin.java        # Framebuffer resize listener
│   └── render/
│       ├── JitterHelper.java       # Halton(2, 3) subpixel phase generator
│       └── VulkanFrameManager.java # Render pass coordinator & telemetry
├── src/test/java/net/metalmod/
│   ├── JitterHelperTest.java       # Halton jitter bounds unit test
│   ├── MetalBridgeTest.java        # FFI capability test
│   └── StandaloneTestRunner.java   # Standalone test runner (no dependencies)
├── build.gradle                    # Gradle build script with compileNative task
├── settings.gradle
└── gradle.properties
```

---

## Building and Testing

### Prerequisites
- Apple Silicon Mac running **macOS 26+**
- Xcode 21+ with Command Line Tools
- CMake 3.28+
- JDK 22+ (JDK 26 recommended)

### 1. Compile Native Library
```bash
cmake -B native/build -S native
cmake --build native/build
```
Produces `native/build/libmetalmod.dylib`.

### 2. Run Standalone Verification Suite
```bash
/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home/bin/javac -d bin \
  src/main/java/net/metalmod/ffi/MetalBridge.java \
  src/main/java/net/metalmod/config/MetalConfig.java \
  src/main/java/net/metalmod/render/JitterHelper.java \
  src/test/java/net/metalmod/StandaloneTestRunner.java

/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home/bin/java \
  --enable-native-access=ALL-UNNAMED -cp bin net.metalmod.StandaloneTestRunner
```

---

## Controls & In-Game Usage

- **`F6`**: Toggle Frame Generation (Metal 4) On / Off
- **`F7`**: Cycle Upscaling Mode (Off -> MetalFX Spatial -> MetalFX Temporal)
- **Config Screen**: Access through Mod Menu or pause menu for custom scaling ratios, ProMotion 120Hz synchronization, and real-time GPU frame time metrics.
