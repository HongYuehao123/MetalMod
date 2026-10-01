#pragma once

// Spatial, perceptual SDR anti-aliasing before MetalFX. This is an edge-directed filter,
// not temporal AA: it cannot recover hidden detail or stabilise samples across frames.
// Work in input texels, clamp border samples, preserve centre alpha and skip flat regions.
static constexpr const char* MMMSpatialShaderSource = R"MSL(
#include <metal_stdlib>
using namespace metal;
struct V { float4 p [[position]]; float2 uv; };
vertex V vs(uint i [[vertex_id]]) {
    float2 p = float2((i << 1) & 2, i & 2);
    V v; v.p = float4(p * 2 - 1, 0, 1); v.uv = float2(p.x, 1 - p.y); return v;
}
fragment float4 fs(V v [[stage_in]], texture2d<float> t [[texture(0)]],
                   sampler s [[sampler(0)]]) { return t.sample(s, v.uv); }
float luminance(float3 c) { return dot(c, float3(0.299, 0.587, 0.114)); }
fragment float4 antialias(V v [[stage_in]], texture2d<float> t [[texture(0)]],
                          sampler s [[sampler(0)]]) {
    float2 texel = 1.0 / float2(t.get_width(), t.get_height());
    float4 centre = t.sample(s, v.uv);
    float3 nw = t.sample(s, v.uv + float2(-1, -1) * texel).rgb;
    float3 ne = t.sample(s, v.uv + float2( 1, -1) * texel).rgb;
    float3 sw = t.sample(s, v.uv + float2(-1,  1) * texel).rgb;
    float3 se = t.sample(s, v.uv + float2( 1,  1) * texel).rgb;
    float3 n = t.sample(s, v.uv + float2(0, -1) * texel).rgb;
    float3 e = t.sample(s, v.uv + float2(1,  0) * texel).rgb;
    float3 w = t.sample(s, v.uv + float2(-1, 0) * texel).rgb;
    float3 south = t.sample(s, v.uv + float2(0, 1) * texel).rgb;
    float lm = luminance(centre.rgb), lnw = luminance(nw), lne = luminance(ne);
    float lsw = luminance(sw), lse = luminance(se);
    float lo = min(lm, min(min(lnw, lne), min(lsw, lse)));
    float hi = max(lm, max(max(lnw, lne), max(lsw, lse)));
    lo = min(lo, min(min(luminance(n), luminance(e)), min(luminance(w), luminance(south))));
    hi = max(hi, max(max(luminance(n), luminance(e)), max(luminance(w), luminance(south))));
    float contrast = hi - lo;
    if (contrast < max(0.03125, hi * 0.125)) return centre;

    // Sample along the local edge rather than blur indiscriminately across it.
    float2 direction = float2(-(lnw + lne - lsw - lse), lnw + lsw - lne - lse);
    float reduce = max((lnw + lne + lsw + lse) * 0.03125, 0.0078125);
    direction = clamp(direction / (min(abs(direction.x), abs(direction.y)) + reduce),
                      float2(-8), float2(8)) * texel;
    float3 a = 0.5 * (t.sample(s, v.uv - direction / 6).rgb
                   + t.sample(s, v.uv + direction / 6).rgb);
    float3 b = a * 0.5 + 0.25 * (t.sample(s, v.uv - direction * 0.5).rgb
                               + t.sample(s, v.uv + direction * 0.5).rgb);
    float lb = luminance(b);
    float3 edge = (lb < lo || lb > hi) ? a : b;
    // A bounded subpixel contribution catches isolated foliage-sized high-frequency details
    // for which the directional estimate cancels. Keep broad edges and flat colours intact.
    float3 average = (n + e + w + south) * 0.25;
    float subpixel = smoothstep(0.0, 1.0, abs(luminance(average) - lm) / contrast);
    return float4(mix(edge, average, 0.5 * subpixel * subpixel), centre.a);
}
)MSL";
