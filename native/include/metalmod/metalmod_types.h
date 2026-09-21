#pragma once

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef enum MetalModScalingMode {
    METALMOD_SCALING_OFF = 0,
    METALMOD_SCALING_SPATIAL = 1,
    METALMOD_SCALING_TEMPORAL = 2
} MetalModScalingMode;

typedef enum MetalModQualityPreset {
    METALMOD_PRESET_NATIVE = 0,       // 1.0x
    METALMOD_PRESET_ULTRA_QUALITY = 1,// 0.77x
    METALMOD_PRESET_QUALITY = 2,      // 0.67x
    METALMOD_PRESET_BALANCED = 3,     // 0.58x
    METALMOD_PRESET_PERFORMANCE = 4,  // 0.50x
    METALMOD_PRESET_ULTRA_PERF = 5    // 0.33x
} MetalModQualityPreset;

typedef struct MetalModConfig {
    uint32_t inputWidth;
    uint32_t inputHeight;
    uint32_t outputWidth;
    uint32_t outputHeight;
    MetalModScalingMode scalingMode;
    bool frameGenerationEnabled;
    float sharpness;               // [0.0, 1.0]
    bool enableHDR;
    bool enableUIOverlay;
    uint32_t targetDisplayFPS;     // e.g. 60 or 120 (ProMotion)
} MetalModConfig;

typedef struct MetalModFrameParams {
    uint64_t frameIndex;
    float deltaTime;               // Time since last frame in seconds
    float jitterOffsetX;           // Subpixel jitter in pixels
    float jitterOffsetY;
    float nearPlane;
    float farPlane;
    float fieldOfView;             // In degrees
    float aspectRatio;             // width / height
    bool resetHistory;             // Camera cut / teleport
    bool isDepthReversed;          // Reversed-Z depth buffer
} MetalModFrameParams;

typedef struct MetalModTelemetry {
    float renderFPS;
    float presentedFPS;
    float gpuFrameTimeMs;
    float upscalerTimeMs;
    float frameGenTimeMs;
    uint64_t totalFramesRendered;
    uint64_t totalFramesPresented;
} MetalModTelemetry;

#ifdef __cplusplus
}
#endif
