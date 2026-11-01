#version 460
// Shadow pass vertex: transforms terrain vertices to the light's clip space.
// Same vertex layout and section offsets as the terrain pipeline; the shadow
// pass loads the light's matrices into MVP.

layout(binding = 0) uniform LightSpaceUBO {
    mat4 LightMVP;
    vec4 ChimeraCameraFraction;
    vec4 ChimeraTexCoordShrink;
};

layout(push_constant) uniform Push {
    vec3 ChunkOffset;
};

layout(location = 0) in ivec4 inPositionLight; // xyz: pos*2048 - 8192
layout(location = 2) in uvec2 inUV;

layout(location = 0) out vec2 outTexCoord;

const vec3 POSITION_SCALE = vec3(1.0 / 2048.0);
const float UV_SCALE = 1.0 / 32768.0;

vec3 sectionOffset() {
    // Three bytes: x in bits [0..8), z in bits [8..16), y in bits [16..24)
    return vec3(bitfieldExtract(ivec3(gl_InstanceIndex) >> ivec3(0, 16, 8), 0, 8));
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

    outTexCoord = texCoord();
    gl_Position = LightMVP * vec4(worldPos, 1.0);
}
