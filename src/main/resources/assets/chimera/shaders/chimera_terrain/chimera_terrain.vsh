#version 460
// Chimera terrain vertex stage (M2).
// Consumes VulkanMod's COMPRESSED_TERRAIN layout: position/light packed in an
// ivec4, UVs as raw u16 pairs, color as a u32. Section offsets arrive per
// instance through a UBO indexed by gl_InstanceIndex; ModelOffset is a push
// constant. Interface must stay byte-compatible with the host's terrain path.

layout(binding = 0) uniform ViewUBO {
    mat4 MVP;
};

layout(binding = 2) uniform SectionData {
    ivec4 SectionOffsets[128];
    vec4 SectionFadeFactors[128];
};

layout(push_constant) uniform Push {
    vec3 ModelOffset;
};

layout(binding = 4) uniform sampler2D LightMap;

layout(location = 0) in ivec4 inPositionLight; // xyz: pos*2048 - 8192, w: packed light
layout(location = 1) in uvec2 inUV;
layout(location = 2) in uint inPackedColor;
layout(location = 3) in int inBlockId;

layout(location = 0) out vec4 outVertexColor;
layout(location = 1) out vec2 outTexCoord;
layout(location = 2) out float outSphericalDistance;
layout(location = 3) out float outCylindricalDistance;
layout(location = 4) out flat float outFadeFactor;
layout(location = 5) out flat int outBlockId;

const float UV_SCALE = 1.0 / 32768.0;
const vec3 POSITION_SCALE = vec3(1.0 / 2048.0);
const vec3 POSITION_BIAS = vec3(4.0);

vec3 unpackSectionOffset(int encoded) {
    // Three signed bytes: x in bits [0..8), z in bits [8..16), y in bits [16..24)
    return vec3(
        float(bitfieldExtract(encoded, 0, 8)),
        float(bitfieldExtract(encoded, 16, 8)),
        float(bitfieldExtract(encoded, 8, 8))
    );
}

vec4 fetchLightmap(uint packedLight) {
    // Block light lives in bits [4..8), sky light in bits [12..16)
    int blockLevel = int((packedLight >> 4u) & 0xFu);
    int skyLevel = int((packedLight >> 12u) & 0xFu);
    return texelFetch(LightMap, ivec2(blockLevel, skyLevel), 0);
}

void main() {
    int encoded = SectionOffsets[gl_InstanceIndex >> 2][gl_InstanceIndex & 3];
    vec3 sectionOrigin = unpackSectionOffset(encoded);

    vec3 worldPos = fma(vec3(inPositionLight.xyz), POSITION_SCALE, ModelOffset + sectionOrigin);

    gl_Position = MVP * vec4(worldPos, 1.0);

    outSphericalDistance = length(worldPos);
    outCylindricalDistance = max(length(worldPos.xz), abs(worldPos.y));

    vec4 baseColor = unpackUnorm4x8(inPackedColor);
    outVertexColor = baseColor * fetchLightmap(uint(inPositionLight.w));

    outFadeFactor = SectionFadeFactors[gl_InstanceIndex >> 2][gl_InstanceIndex & 3];

    outBlockId = inBlockId;

    outTexCoord = vec2(inUV) * UV_SCALE;
}
