#version 460
// Chimera terrain fragment stage: VulkanMod's terrain shading (atlas times
// vertex colour, alpha cutout, spherical linear_fog) plus the fixed
// directional shadow.

layout(binding = 2) uniform sampler2D Sampler0;

layout(binding = 4) uniform sampler2D ShadowMap;

layout(binding = 1) uniform FragmentUBO {
    vec4 FogColor;
    float FogStart;
    float FogEnd;
    float AlphaCutout;
};

layout(location = 0) in vec4 inVertexColor;
layout(location = 1) in vec2 inTexCoord;
layout(location = 2) in float inVertexDistance;
layout(location = 3) in vec4 inLightSpacePos;

layout(location = 0) out vec4 outFragColor;

const float SHADOW_BIAS = 0.0025;

float sampleShadow(vec4 lightSpacePos) {
    // VulkanMod uses a zero-to-one NDC Z range. Only X and Y need the
    // normalized texture-coordinate transform. The shadow depth attachment
    // is reversed-Z, so convert it to the forward legacy window before the
    // comparison used by the fixed terrain path.
    vec3 projCoords = lightSpacePos.xyz / lightSpacePos.w;
    projCoords.xy = projCoords.xy * 0.5 + 0.5;
    projCoords.y = 1.0 - projCoords.y;

    if (projCoords.z > 1.0 || projCoords.x < 0.0 || projCoords.x > 1.0 || projCoords.y < 0.0 || projCoords.y > 1.0) {
        return 1.0; // Outside shadow map
    }

    float storedDepth = 1.0 - texture(ShadowMap, projCoords.xy).r;
    return (projCoords.z - SHADOW_BIAS > storedDepth) ? 0.3 : 1.0;
}

void main() {
    vec4 color = texture(Sampler0, inTexCoord) * inVertexColor;
    if (color.a < AlphaCutout) {
        discard;
    }

    // Directional shadow
    color.rgb *= sampleShadow(inLightSpacePos);

    // VulkanMod's linear_fog.
    outFragColor = inVertexDistance <= FogStart ? color
            : mix(color, FogColor, smoothstep(FogStart, FogEnd, inVertexDistance) * FogColor.a);
}
