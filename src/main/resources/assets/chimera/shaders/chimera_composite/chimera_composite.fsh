#version 460
// Composite stage: samples the HDR scene color and writes the post-processed
// result. M3 ships as an identity pass so the multi-segment machinery is
// provably neutral; tone mapping / grading stack on from here.

layout(binding = 0) uniform sampler2D SceneColor;

layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(SceneColor, inUv);
}
