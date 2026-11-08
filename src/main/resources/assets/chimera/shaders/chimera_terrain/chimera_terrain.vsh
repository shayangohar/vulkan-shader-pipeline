#version 460
// Chimera terrain vertex stage.
// Consumes the extended terrain layout: VulkanMod's compressed position/light,
// colour and UV prefix plus material attributes at locations 3 and 4. The
// draw's base instance encodes the section's offset within its area;
// ChunkOffset, the area origin relative to the camera, is a push constant.

layout(binding = 0) uniform ViewUBO {
    mat4 MVP;
    mat4 LightMVP;
    vec4 ChimeraCameraFraction;
    vec4 ChimeraTexCoordShrink;
};

layout(push_constant) uniform Push {
    vec3 ChunkOffset;
};

layout(binding = 3) uniform sampler2D LightMap;

layout(location = 0) in ivec4 inPositionLight; // xyz: pos*2048 - 8192, w: packed light
layout(location = 1) in vec4 inColor;
layout(location = 2) in uvec2 inUV;

layout(location = 0) out vec4 outVertexColor;
layout(location = 1) out vec2 outTexCoord;
layout(location = 2) out float outVertexDistance;
layout(location = 3) out vec4 outLightSpacePos;

const float UV_SCALE = 1.0 / 32768.0;
const vec3 POSITION_SCALE = vec3(1.0 / 2048.0);

vec3 sectionOffset() {
    // Three bytes: x in bits [0..8), z in bits [8..16), y in bits [16..24)
    return vec3(bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8));
}

vec4 fetchLightmap(uint packedLight) {
    // Block light lives in bits [4..8), sky light in bits [12..16)
    int blockLevel = int((packedLight >> 4u) & 0xFu);
    int skyLevel = int((packedLight >> 12u) & 0xFu);
    return texelFetch(LightMap, ivec2(blockLevel, skyLevel), 0);
}

// Watertight across sections and areas, as the pack preamble's chimeraWorldPosition: the exact
// section-local position plus the area's whole-block offset, then the shared camera fraction.
vec3 cameraRelativePosition() {
    vec3 areaBlocks = round(ChunkOffset + ChimeraCameraFraction.xyz);
    precise vec3 blocks = fma(vec3(inPositionLight.xyz), POSITION_SCALE, sectionOffset()) + areaBlocks;
    precise vec3 position = blocks - ChimeraCameraFraction.xyz;
    return position;
}

// The pack preamble's chimeraTexCoord0Value: the word's step toward the quad's centre, taken back
// less one sub-texel, so the coordinate sits just inside its sprite.
vec2 texCoord() {
    vec2 stepUp = vec2(greaterThan(inUV, uvec2(0x7FFFu))) * 2.0 - 1.0;
    return vec2(inUV & 0x7FFFu) * UV_SCALE - stepUp * ChimeraTexCoordShrink.xy;
}

void main() {
    vec3 worldPos = cameraRelativePosition();

    gl_Position = MVP * vec4(worldPos, 1.0);
    outVertexDistance = length(worldPos);

    vec4 baseColor = inColor;
#ifdef CHIMERA_SEPARATE_AO
    // separateAo meshes carry the light pipeline's brightness in alpha (Iris); terrain
    // vertex alpha is otherwise always 1, so fold it into RGB and keep the block opaque.
    baseColor = vec4(baseColor.rgb * baseColor.a, 1.0);
#endif
    outVertexColor = baseColor * fetchLightmap(uint(inPositionLight.w));

    outLightSpacePos = LightMVP * vec4(worldPos, 1.0);

    outTexCoord = texCoord();
}
