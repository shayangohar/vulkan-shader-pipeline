#version 460
// Chimera terrain vertex stage.
// Consumes the extended terrain layout: VulkanMod's compressed position/light,
// colour and UV prefix plus material attributes at locations 3 and 4. The
// draw's base instance encodes the section's offset within its area;
// ChunkOffset, the area origin relative to the camera, is a push constant.

layout(binding = 0) uniform ViewUBO {
    mat4 MVP;
    mat4 LightMVP;
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

void main() {
    vec3 worldPos = fma(vec3(inPositionLight.xyz), POSITION_SCALE, ChunkOffset + sectionOffset());

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

    outTexCoord = vec2(inUV) * UV_SCALE;
}
