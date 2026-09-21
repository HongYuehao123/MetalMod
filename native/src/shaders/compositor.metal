#include <metal_stdlib>
using namespace metal;

struct VertexOut {
    float4 position [[position]];
    float2 uv;
};

// Full-screen triangle vertex shader (no vertex buffer needed)
vertex VertexOut fullScreenVertexShader(uint vertexID [[vertex_id]]) {
    VertexOut out;
    // Map vertexID (0, 1, 2) to cover full clip space [-1, 1]
    out.uv = float2((vertexID << 1) & 2, vertexID & 2);
    out.position = float4(out.uv * float2(2.0f, -2.0f) + float2(-1.0f, 1.0f), 0.0f, 1.0f);
    return out;
}

// Alpha blend fragment shader to composite full-res UI over the upscaled/generated game frame
fragment float4 compositeUIFragmentShader(
    VertexOut in [[stage_in]],
    texture2d<float, access::sample> worldTexture [[texture(0)]],
    texture2d<float, access::sample> uiTexture [[texture(1)]],
    sampler textureSampler [[sampler(0)]]
) {
    float4 worldColor = worldTexture.sample(textureSampler, in.uv);
    float4 uiColor = uiTexture.sample(textureSampler, in.uv);

    // Standard pre-multiplied / straight alpha blend
    // uiColor.a is the opacity of the UI
    float3 finalRGB = mix(worldColor.rgb, uiColor.rgb, uiColor.a);
    return float4(finalRGB, 1.0f);
}

// Passthrough fragment shader (when UI texture is not provided)
fragment float4 passthroughFragmentShader(
    VertexOut in [[stage_in]],
    texture2d<float, access::sample> sourceTexture [[texture(0)]],
    sampler textureSampler [[sampler(0)]]
) {
    return sourceTexture.sample(textureSampler, in.uv);
}
