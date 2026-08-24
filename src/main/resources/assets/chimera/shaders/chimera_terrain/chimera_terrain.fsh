#version 460
// Chimera terrain fragment stage (M2).
// Atlas sampling reproduces the host's magnification behavior: texel-center
// snapping driven by screen-space derivatives, with an optional rotated-grid
// supersampling path when the host enables it. Fog is applied as the max of
// two linear ramps (spherical/environment and cylindrical/render distance).

layout(binding = 3) uniform sampler2D Sampler0;

layout(binding = 1) uniform FragmentSharedUBO {
    vec4 FogColor;
    float FogEnvironmentalStart;
    float FogEnvironmentalEnd;
    float FogRenderDistanceStart;
    float FogRenderDistanceEnd;
    float FogSkyEnd;
    float FogCloudsEnd;
    float AlphaCutout;
    ivec2 TextureSize;
    vec2 TexelSize;
    int UseRgss;
};

layout(location = 0) in vec4 inVertexColor;
layout(location = 1) in vec2 inTexCoord;
layout(location = 2) in float inSphericalDistance;
layout(location = 3) in float inCylindricalDistance;
layout(location = 4) in flat float inFadeFactor;

layout(location = 0) out vec4 outFragColor;

vec4 sampleNearest(sampler2D atlas, vec2 uv, vec2 pixelSize, vec2 du, vec2 dv, vec2 screenTexelSize) {
    vec2 uvInTexels = uv / pixelSize;
    vec2 texelCenter = round(uvInTexels) - 0.5;
    vec2 offsetFromCenter = uvInTexels - texelCenter;

    // Slide toward the texel center proportionally to how large the texel is on screen.
    offsetFromCenter = (offsetFromCenter - 0.5) * pixelSize / screenTexelSize + 0.5;
    offsetFromCenter = clamp(offsetFromCenter, 0.0, 1.0);

    vec2 snappedUv = (texelCenter + offsetFromCenter) * pixelSize;
    return textureGrad(atlas, snappedUv, du, dv);
}

vec4 sampleNearest(sampler2D atlas, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 screenTexelSize = sqrt(du * du + dv * dv);
    return sampleNearest(atlas, uv, pixelSize, du, dv, screenTexelSize);
}

// Four rotated-grid subsamples blended across a mip transition band.
vec4 sampleRotatedGrid(sampler2D atlas, vec2 uv, vec2 pixelSize) {
    vec2 du = dFdx(uv);
    vec2 dv = dFdy(uv);
    vec2 screenTexelSize = sqrt(du * du + dv * dv);
    float maxScreenTexel = max(screenTexelSize.x, screenTexelSize.y);
    float minPixelSize = min(pixelSize.x, pixelSize.y);

    float transitionStart = minPixelSize;
    float transitionEnd = minPixelSize * 2.0;
    float blendFactor = smoothstep(transitionStart, transitionEnd, maxScreenTexel);

    float duLength = length(du);
    float dvLength = length(dv);
    float minDerivative = min(duLength, dvLength);
    float maxDerivative = max(duLength, dvLength);
    float effectiveDerivative = sqrt(minDerivative * maxDerivative);

    float mipExact = max(0.0, log2(effectiveDerivative / minPixelSize));

    const vec2 SUBSAMPLE_OFFSETS[4] = vec2[](
        vec2( 0.125,  0.375),
        vec2(-0.125, -0.375),
        vec2( 0.375, -0.125),
        vec2(-0.375,  0.125)
    );

    float mipLow = floor(mipExact);
    float mipHigh = mipLow + 1.0;
    float mipBlend = fract(mipExact);

    vec4 lowAccum = vec4(0.0);
    vec4 highAccum = vec4(0.0);
    for (int i = 0; i < 4; ++i) {
        vec2 sampleUv = uv + SUBSAMPLE_OFFSETS[i] * pixelSize;
        lowAccum += textureLod(atlas, sampleUv, mipLow);
        highAccum += textureLod(atlas, sampleUv, mipHigh);
    }
    lowAccum *= 0.25;
    highAccum *= 0.25;

    vec4 rotatedGridColor = mix(lowAccum, highAccum, mipBlend);
    vec4 nearestColor = sampleNearest(atlas, uv, pixelSize, du, dv, screenTexelSize);
    return mix(nearestColor, rotatedGridColor, blendFactor);
}

float fogRamp(float distance, float start, float end) {
    if (distance <= start) {
        return 0.0;
    }
    if (distance >= end) {
        return 1.0;
    }
    return (distance - start) / (end - start);
}

void main() {
    vec4 sampled = UseRgss == 1
        ? sampleRotatedGrid(Sampler0, inTexCoord, TexelSize)
        : sampleNearest(Sampler0, inTexCoord, TexelSize);

    vec4 color = sampled * inVertexColor;

    // Sections fade in by blending against fog color before alpha rejection.
    color = mix(FogColor * vec4(1.0, 1.0, 1.0, color.a), color, inFadeFactor);

    if (color.a < AlphaCutout) {
        discard;
    }

    float fogValue = max(
        fogRamp(inSphericalDistance, FogEnvironmentalStart, FogEnvironmentalEnd),
        fogRamp(inCylindricalDistance, FogRenderDistanceStart, FogRenderDistanceEnd)
    );

    outFragColor = vec4(mix(color.rgb, FogColor.rgb, fogValue * FogColor.a), color.a);
}
