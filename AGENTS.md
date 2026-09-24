# MetalMod — Agent Instructions & Operating Manual

> **Purpose:** This document is the authoritative operating manual for AI coding agents (Claude, Gemini, GPT, Cursor, Devin, etc.) working on the MetalMod repository. Read this file before proposing or making changes to the codebase.

---

## 1. Project Overview & Architecture

### What MetalMod Is
MetalMod is a **native Metal graphics backend** for Minecraft 26.2 on macOS (Apple Silicon).
Instead of translating Vulkan through MoltenVK, it plugs into Minecraft's Blaze3D backend abstraction (`com.mojang.blaze3d.systems.GpuBackend` / `GpuDeviceBackend`) and directly drives native Metal primitives (`MTLDevice`, `MTLCommandQueue`, `CAMetalLayer`, `MTLRenderPipelineState`, `MTLTexture`, `MTLBuffer`).

### What MetalMod Is NOT
- **NOT a MoltenVK wrapper or patch.** The retired "MetalFX on top of MoltenVK / `VK_EXT_metal_objects`" architecture has been completely removed. Do not attempt to hook into Vulkan or MoltenVK.
- **NOT a reimplementation of Minecraft rendering.** Unlike VulkanMod (which had to rewrite rendering when Blaze3D had no backend abstraction), MetalMod is a clean driver implementation (~134 interface members across 12 Blaze3D backend classes).
- **NOT dependent on Sodium or Iris.** Sodium compatibility is explicitly not pursued (it targets Blaze3D's internal abstraction and does not ease shaderpack or ray tracing work).

### Dual-Language Stack
1. **Java 22+ (Panama FFI):** Implements Blaze3D interfaces (`net.metalmod.backend.*`), Mixin hooks (`net.metalmod.mixin.*`), configuration GUI, F3 telemetry, and Phase 6 dynamic lighting management (`net.metalmod.lighting.*`).
2. **Objective-C++ (`libmetalmod.dylib`):** Native substrate in `native/src/` driving `Metal`, `MetalFX`, `QuartzCore`, and Mach VM UMA telemetry via C ABI functions (`mmm_*`, `mmm_fx_*`, `metalmod_uma_*`).

### Phase Roadmap Status
- **Phases 0–5 (Foundations to Vanilla Parity): COMPLETE.** Backend selection, resources, pipelines, draw calls, all 87 vanilla pipelines + 9 post-processing passes compile, and visual/performance parity confirmed on Apple Silicon (M4 Pro).
- **Phase 6 (Dynamic Lighting): COMPLETE.** Light snapshotting, moving sources (held/dropped items, entities, moving blocks), 16-block clustered grid evaluator, and published light-record ABI v1 (`docs/lighting-abi.md`).
- **Phase 7 (MetalFX & Pacing): 7A DONE (verified offline, awaiting one in-game run); 7B IMPLEMENTED (camera reprojection plus per-object stamps, live encode, resets; verified offline, awaiting an in-game evaluation); 7C NOT STARTED.** Native MetalFX spatial and temporal scalers (`native/src/metalmod_metalfx.mm`), render-resolution scaling of the level into its own target, projection jitter and history lifecycle. Temporal is driven by a native motion module (`native/src/metalmod_motion.mm`): a compute kernel reprojects the level depth for the camera and static geometry, and an instanced screen-space overlay stamps entities, particles and pushed blocks with their own previous positions over a depth test. `metalfx/SceneMotion` owns the current/previous-transform contract shared with Phase 8C; frame generation needs frame-loop pacing on top of this motion. Per-increment record: `docs/phase7-plan.md`.
- **Phase 8 (Native Material & Lighting Foundations): PLANNED.** Linear-light/HDR composition, G-buffer layouts, Phase 8B ray visibility occlusion/shadowing.
- **Phase 9 (Hybrid Ray Tracing): PLANNED.** Metal ray tracing pipeline (`MTLAccelerationStructure`).
- **Optional (GLSL Shaderpacks): DEFERRED.** Preserved as optional compatibility; not an RT prerequisite.

---

## 2. Build System & Golden Rules (CRITICAL)

### Golden Rule #1: NEVER Run Gradle
- **`./gradlew` does NOT exist.**
- `build.gradle` is **NOT** a working mod build (it is incomplete/legacy).
- **NEVER** run `./gradlew` or attempt to invoke Gradle tasks.

### Golden Rule #2: The Authoritative Build Script
Always build the mod using:
```bash
./scripts/build_mod.sh
```

#### What `build_mod.sh` does:
1. **Compiles native library:** Runs CMake in `native/` (`cmake -B build -S . -DCMAKE_BUILD_TYPE=Release && cmake --build build`) to produce `native/build/libmetalmod.dylib`.
2. **Resolves classpath from real Minecraft instance:** Runs `scripts/build_classpath.py` against the version JSON of the test instance (`$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2`).
3. **Compiles Java sources with `javac --release 22`:** Compiles against the **REAL Minecraft client JAR** (not API stubs!). `javac` validates every Minecraft API call.
4. **Packages mod JAR:** Copies `fabric.mod.json`, `metalmod.mixins.json`, and embeds `libmetalmod.dylib` under `natives/` into `build/libs/metalmod-1.0.0.jar`.
5. **Compiles standalone tests:** Compiles `src/test/java` (skipping JUnit-only files) into `build/test-classes`.

#### Environment Configuration:
- `JAVA_HOME`: Default is `/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home` (JDK 22+ required).
- `METALMOD_MC_INSTANCE`: Override the Minecraft instance path (defaults to `$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2`).

---

## 3. The 5 Mandatory Offline Verification Gates

Before trusting any code changes or declaring work complete, an agent must verify that all applicable offline verification gates pass. The original five are the baseline; 6 and 7 were added in Phase 7 because the scaling frame and the mixin targets are not observable any other way without launching the game:

| Gate | Command | Pass Criteria | What It Exercises |
|---|---|---|---|
| **1. Mod Build** | `./scripts/build_mod.sh` | `SUCCESS -> build/libs/metalmod-1.0.0.jar` | Native dylib compilation, Java compilation against real client JAR, mod packaging, test compilation. |
| **2. Native Smoke Test** | `./native/build/metalmod_smoke`<br>*(or `./scripts/run_smoke.sh`)* | `ALL CHECKS PASSED` | Native device, queue, command buffers, textures, views, buffers, pipelines, draws, clear, staging uploads, texel buffers, fences, the MetalFX spatial/temporal scalers, and the Phase 7B motion kernel and screen-space overlay. |
| **3. Shader Inventory** | `./tools/shader_inventory/run.sh` | `static 87/87`, `post 9/9`, no diagnostics | Compiles all 87 vanilla pipelines and 9 post-processing passes offline through GLSL → SPIR-V → MSL. |
| **4. Pixel Render Check** | `./tools/render_check/run.sh` | `RENDER CHECK PASSED` (173 pixel assertions) | Offscreen real pipeline execution: GUI, terrain, entities, cutout, lines, mipmaps, scissor conversions, blend modes, lightmap, Phase 6 dynamic lighting paths, Phase 7A MetalFX spatial upscaling. |
| **5. Standalone Tests** | See runner command below | `ALL TESTS PASSED SUCCESSFULLY!` | Panama FFI bridge loading, UMA allocator routing, format mappings, sub-buffer offsets, Phase 6 cluster grid / ABI byte offsets. |
| **6. Phase 7 Scaling Check** | `./tools/scaling_check/run.sh` | `SCALING CHECK PASSED` (91 assertions) | The scaled level target built from the engine's own `MainTarget`, written through the engine's own `FrameGraphBuilder` as an imported external resource, upscaled to native, resized, released; the jitter sequence; and the Phase 7B temporal path - the scene contract, the depth-derived motion field's values and conventions, the per-object stamps and their depth test, the reset lifecycle, and the effect's offscreen cost against spatial at the same sizes. |
| **7. Mixin Target Check** | `./tools/mixin_check/run.sh` | `MIXIN CHECK PASSED` (72 checks) | Every mixin's target class, injected method, `@Shadow` member and `@At` descriptor resolved against the real client jar. Catches the quiet failures `defaultRequire: 0` produces. |

#### Running Gate 5 (Standalone Tests):
```bash
INSTANCE="${METALMOD_MC_INSTANCE:-$HOME/Documents/.minecraft/versions/MetalMod_Test_26.2}"
CLASSPATH="build/classes:build/test-classes:$(python3 scripts/build_classpath.py "$INSTANCE")"
/Library/Java/JavaVirtualMachines/jdk-26.jdk/Contents/Home/bin/java \
  --enable-native-access=ALL-UNNAMED \
  -cp "$CLASSPATH" net.metalmod.StandaloneTestRunner
```

### Execution Sandbox & Hardware Access Note
Metal device creation (`MTLCreateSystemDefaultDevice()`) and reading the Minecraft instance JSON/JAR require access to host resources. If running in a restricted sandbox where GPU access or `$HOME/Documents` is isolated:
- Bypassing sandbox or providing explicit directory access is required for executing Metal runtime tests and reading the Minecraft client jar.
- When native smoke tests fail with `no Metal device`, verify GPU accessibility in the execution environment.

---

## 4. Key Architectural Invariants & Gotchas

### 4.1 Coordinate Systems & The Scissor Trap (BUG-001 & BUG-025)
- **Convention:** Minecraft / OpenGL uses a **bottom-up** coordinate system ($Y=0$ at bottom-left). Metal uses a **top-left** coordinate system ($Y=0$ at top-left).
- **Viewport Y-Flip:** Passes rendering to textures that need Y-flipping (e.g. `"UI "` offscreen targets, PIP entity previews, item atlas) use a negative viewport height and reverse front-face winding (`Clockwise` vs `CounterClockwise`).
- **Scissor Conversion Invariant:** In `MetalRenderPassBackend.enableScissor`, the bottom-up Y coordinate is converted to top-left **ONLY IF** the pass viewport is NOT already flipped. On an already-flipped pass (`needsYFlip`), the engine's Y coordinate matches the target orientation and must be passed through directly without conversion. (Converting on an already-flipped pass clips off the target and caused disappearing icons in BUG-025).

### 4.2 Storage Modes & Apple Silicon UMA
- **Shared Storage by Default:** Apple Silicon has Unified Memory Architecture (UMA). All `MTLTexture` and `MTLBuffer` allocations default to `MTLResourceStorageModeShared`.
- **Private Storage Regression:** Dedicated private storage for render targets was tested and measured **9% slower** on an M4 Pro route due to staging copy overhead. Private storage is gated behind `-Dmetalmod.privateTextures=all` and remains **OFF** by default.
- **Utility Submission Batching:** Buffer writes (`mmm_write_buffer_bytes`), buffer copies, and texture copies share a single pending command buffer with a shared blit encoder per frame. Do not commit individual command buffers for small utility uploads (batching cut underground chunk-mesh upload cost by 95%).

### 4.3 Shader Pipeline & Uniform Block Hazard
- **Compilation Chain:** GLSL `#moj_import` → `lwjgl-shaderc` (SPIR-V) → `SPIRV-Cross` (MSL) → `MTLRenderPipelineState`.
- **Uniform Block Indexing Hazard:** On Apple Silicon Metal, dynamic indexing into arrays inside uniform blocks (`std140`) is **unreliable** and can read zero.
  - *Hard Rule:* For table-shaped data (such as the Phase 6 cluster grid), publish the data as an **RGBA32F texture** and access texels using `texelFetch`. Do not rely on dynamic uniform block array indexing.
- **Lazy Compilation:** Pipelines not precompiled by vanilla startup are compiled lazily on first draw.

### 4.4 Minecraft 26.2 Named Mappings & Mixin Annotations
- **Named Mappings:** The Minecraft 26.2 client JAR uses **named Mojang mappings** (e.g. `RenderPipeline`, `GpuDevice`, `Minecraft`). There are **ZERO** `class_XXXX` intermediary mappings. Never write obfuscated mappings.
- **Annotation Retention Rules:**
  - `@Mixin` has `CLASS` retention.
  - `@Shadow`, `@Inject`, `@ModifyVariable`, `@At`, `@Accessor` have `RUNTIME` retention!
  - If injector/accessor annotations lose runtime retention, the code compiles fine but Mixin silently ignores them at runtime.
- **Mixin Target Rejection:** Fabric Mixin rejects an entire mixin file if even one target class in `targets` is unresolved. Keep mixins focused.

### 4.5 Indirect Draws Are No-Ops
- `drawIndirect` and `drawIndexedIndirect` are currently unimplemented no-ops.
- `DeviceFeatures` flags for indirect drawing are explicitly returned as `false`.
- Vanilla Minecraft has no engine caller for multiDrawIndexed; chunk mesh rendering loops per section via `drawMultipleIndexed`.

### 4.6 Phase 6 Dynamic Lighting ABI Contract
- Documented in detail in `docs/lighting-abi.md`.
- **ABI Version: 1** (verified in `MetalModMeta.y`). Maximum capacity: 64 point lights.
- Published once per presented frame:
  1. `MetalModLightSet` (uniform buffer, std140, 2064 bytes): count, ABI version, array of `(posRadius, colorIntensity)`.
  2. `MetalModLightGrid` (uniform buffer, 16 bytes): camera-relative window origin and cell size (16 blocks).
  3. `MetalModLightData` (RGBA32F texture, 1 row, 8849 texels): cluster table and light records.
- Positions are in camera-relative world space.
- Only moving/dynamic sources (held/dropped items, entities, moving blocks) emit dynamic light. Placed torches and blocks rely on vanilla baked lighting to prevent double-lighting. Occlusion is deferred to Phase 8B.

---

## 5. Directory Structure & Key Files

```
MetalMod/
├── AGENTS.md                           # This operating manual
├── README.md                           # High-level architecture and user guide
├── HANDOFF.md                          # Latest engineering status, verified benchmarks, phase state
├── ROADMAP.md                          # Full architectural roadmap and phase definitions
├── TESTING.md                          # Test procedures, benchmarks, and verification checklists
├── bug.md                              # Open and fixed defect register (BUG-XXX format)
│
├── native/                             # Objective-C++ / Metal native library
│   ├── CMakeLists.txt                  # Native build configuration
│   ├── include/metalmod/
│   │   ├── metalmod_metal.h            # Exported C API for Metal backend (mmm_*)
│   │   ├── metalmod_metalfx.h          # Exported C API for MetalFX upscaling (mmm_fx_*)
│   │   ├── metalmod_motion.h           # Exported C API for the temporal motion kernel (mmm_motion_*)
│   │   ├── metalmod_memory.h           # UMA telemetry & Mach VM memory pressure
│   │   └── metalmod_types.h            # Shared types and enum definitions
│   ├── src/
│   │   ├── metalmod_metal.mm           # Native Metal substrate (device, layer, pipelines, passes, blit)
│   │   ├── metalmod_metalfx.mm         # Native MetalFX spatial/temporal integration
│   │   ├── metalmod_motion.mm          # Camera-reprojection motion vectors for the temporal scaler
│   │   └── metalmod_memory.mm          # UMA memory pool and kernel pressure handler
│   └── tests/
│       └── metal_smoke.mm              # Native smoke test suite
│
├── scripts/
│   ├── build_mod.sh                    # Canonical build script
│   ├── build_classpath.py              # Classpath generator from Minecraft version JSON
│   ├── dump_backend_api.sh             # Dumps Blaze3D backend interfaces from client jar
│   └── run_smoke.sh                    # Native smoke test runner
│
├── tools/
│   ├── render_check/run.sh             # Gate 4: Offscreen real pipeline pixel verification
│   ├── scaling_check/run.sh            # Gate 6: Phase 7 scaled target, MetalFX upscale and temporal path, offscreen
│   ├── mixin_check/run.sh              # Gate 7: Mixin injection points against the client jar
│   ├── shader_inventory/run.sh         # Gate 3: Compiles all 87 vanilla pipelines + 9 post passes
│   └── shader_repro/run.sh             # Offline reproduction/debug of single shader pairs
│
├── src/main/java/net/metalmod/
│   ├── backend/                        # Blaze3D GpuBackend implementation (MetalDevice, MetalRenderPipeline, etc.)
│   ├── client/                         # Client entrypoint and config screens
│   ├── config/                         # Configuration persistence (metalmod.properties)
│   ├── debug/                          # F3 debug overlay, performance capture (F8), route player (F7)
│   ├── ffi/                            # Panama foreign function bindings (MetalBridge)
│   ├── lighting/                       # Phase 6 dynamic lighting (collector, cluster grid, snapshot, variants)
│   ├── metalfx/                        # Phase 7 render scale, world target, MetalFX, jitter, scene motion
│   ├── memory/                         # Memory telemetry and pressure management
│   └── mixin/                          # Mixin injectors for backend selection and hooks
│
├── src/test/java/net/metalmod/         # Standalone verification suite (Gate 5)
├── docs/                               # Architectural plans and specifications
│   ├── backend-api.md                  # Blaze3D 134-member API dump
│   ├── lighting-abi.md                 # Phase 6 GPU light record ABI contract
│   ├── phase6-plan.md                  # Phase 6 execution plan and verification log
│   ├── phase7-plan.md                  # Phase 7 per-increment record and integration contract
│   └── raytracing-plan.md              # Long-term Phase 9 ray tracing plan
└── config/
    └── metalmod.properties             # Runtime configuration properties
```

---

## 6. Diagnostic & Debugging Toolkit

### Reproducing Shader Issues Offline
When the game or build fails with shader compilation errors, do not guess:
```bash
./tools/shader_repro/run.sh <vsh-path-in-jar> <fsh-path-in-jar> [colorFormat] [depthFormat]

# Example:
./tools/shader_repro/run.sh \
  assets/minecraft/shaders/core/animate_sprite.vsh \
  assets/minecraft/shaders/core/animate_sprite_interpolate.fsh
```
This prints the SPIR-V interface locations, both generated MSL sources, and Metal's compiler diagnostic output.

### Dumping Generated MSL
Add `-Dmetalmod.dumpMsl=<substring>` to any tool or JVM launch command:
```bash
-Dmetalmod.dumpMsl=terrain    # dumps shaders matching "terrain"
-Dmetalmod.dumpMsl=all        # dumps every compiled shader pair
```

### Runtime Configuration & Toggles
Settings can be toggled via `config/metalmod.properties`, JVM `-D` flags, or the in-game GUI: **Options → MetalMod… → MetalFX Upscaling** (Phase 7 render scale and upscaler - spatial, temporal or off - with a live status line) and **Options → MetalMod… → Lighting** (Phase 6).
*Precedence:* In-Game UI > JVM `-D` flag > `metalmod.properties`.

| Flag / Property | Description | Default |
|---|---|---|
| `-Dmetalmod.metalBackend=true` / `preferMetalBackend=true` | Enables native Metal backend (requires restart) | `false` |
| `-Dmetalmod.dynamicLights=true` / `enableDynamicLights=true` | Enables Phase 6 dynamic lighting (held/dropped items, entities) | `false` |
| `-Dmetalmod.clusteredLights=true` / `enableClusteredLights=true` | Evaluates dynamic lights via 16-block clustered grid | `false` |
| `-Dmetalmod.pointLightProof=true` / `enablePointLightProof=true` | Single camera-centred amber test light | `false` |
| `-Dmetalmod.privateTextures=all` | Forces private storage mode on render targets (measured slower) | `false` |
| `-Dmetalmod.renderScale=0.5` / `renderScale=0.5` | Fraction of the window the world renders at; 1.0 is off (Phase 7A) | `1.0` |
| `-Dmetalmod.upscaler=spatial` / `upscaler=spatial` | `spatial`, `temporal`, `off`. Temporal needs a motion source and an `RG16Float`-capable device; when it cannot run the backend falls back to Spatial and says why | `spatial` |
| `upscalingNotice=true` | Announce an upscaling change as an in-world toast | `true` |

### In-Game Performance Capture & Telemetry
- **F3 Overlay:** MetalMod section reports selected backend, render resolution, CPU/GPU wait time proxy, active dynamic light count, health counters (`unbound/missingAttr/failed`), the Phase 7 `upscale` line (sizes, effect, frame counts, failure reason), the Phase 7B `motion` line when temporal is running (producer, objects captured and stamped, dispatched frames, drops, resets and their reason) or a `temporal not running` line with the fallback reason, and the `pacing` line (last interval, p95, steady share, dropped count).
- **F8 Key:** Starts a 60-second performance capture (saves summary and per-frame CSV under `debug/metalmod/`).
- **F6 Key:** Cycles off / spatial / temporal in place, at one spot, so the three modes can be compared on the same scene; the pixel sample and the F3 `frame` line are the reading.
- **F7 Key:** Records or plays back reproducible movement routes for rigorous A/B benchmarking.

---

## 7. Agent Operational Protocol

When working on tasks in this repository, follow this workflow:

1. **Pre-flight Check:**
   - Inspect `HANDOFF.md` for current project status.
   - Check `bug.md` if addressing defects.
   - Run `git status` to observe any untracked or in-progress files.
2. **Implementation:**
   - Write idiomatic Java 22 / Objective-C++ code matching existing conventions.
   - Never use stub JARs or Gradle.
   - Respect coordinate conventions (Y-flip and scissor rules).
   - Maintain API docstrings and comments.
3. **Verification:**
   - Run `./scripts/build_mod.sh`.
   - Run `./native/build/metalmod_smoke` (or `./scripts/run_smoke.sh`).
   - Run `./tools/shader_inventory/run.sh`.
   - Run `./tools/render_check/run.sh`.
   - Run `net.metalmod.StandaloneTestRunner`.
4. **Documentation & Tracking:**
   - Update `HANDOFF.md` with factual status changes.
   - If fixing a bug, document root cause and verification in `bug.md` (BUG-XXX).
   - If extending lighting or rendering contracts, update `docs/lighting-abi.md` or relevant plans.
