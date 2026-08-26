#version 460
// Shadow pass fragment: outputs depth for shadow map sampling, with an
// alpha cutout so cutout geometry (foliage) does not cast solid shadows.
// Threshold matches TerrainRenderType.CUTOUT.alphaCutout (0.5); solid
// texels have alpha 1 and are unaffected.

layout(binding = 3) uniform sampler2D Sampler0;

layout(location = 0) in vec2 inTexCoord;
layout(location = 0) out vec4 outColor;

void main() {
    vec4 texColor = texture(Sampler0, inTexCoord);
    if (texColor.a < 0.5) {
        discard;
    }
    outColor = vec4(gl_FragCoord.z, 0.0, 0.0, 1.0);
}
