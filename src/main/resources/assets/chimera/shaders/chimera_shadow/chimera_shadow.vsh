#version 460
// Shadow pass vertex: transforms terrain vertices to the light's clip space.
// Same vertex layout and section-offset system as the terrain pipeline.

layout(binding = 0) uniform LightSpaceUBO {
    mat4 LightMVP;
};

layout(binding = 1) uniform SectionData {
    ivec4 SectionOffsets[128];
};

layout(push_constant) uniform Push {
    vec3 ModelOffset;
};

layout(location = 0) in ivec4 inPositionLight; // xyz: pos*2048 - 8192
layout(location = 1) in uvec2 inUV;
layout(location = 2) in uint inPackedColor;

const vec3 POSITION_SCALE = vec3(1.0 / 2048.0);
const vec3 POSITION_BIAS = vec3(4.0);

vec3 unpackSectionOffset(int encoded) {
    return vec3(
        float(bitfieldExtract(encoded, 0, 8)),
        float(bitfieldExtract(encoded, 16, 8)),
        float(bitfieldExtract(encoded, 8, 8))
    );
}

void main() {
    int encoded = SectionOffsets[gl_InstanceIndex >> 2][gl_InstanceIndex & 3];
    vec3 sectionOrigin = unpackSectionOffset(encoded);

    vec3 worldPos = fma(vec3(inPositionLight.xyz), POSITION_SCALE, ModelOffset + sectionOrigin);

    gl_Position = LightMVP * vec4(worldPos, 1.0);
}
