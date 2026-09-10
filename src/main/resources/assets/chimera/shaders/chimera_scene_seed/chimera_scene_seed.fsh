#version 460

layout(binding = 0) uniform sampler2D SceneColor;
layout(binding = 1) uniform sampler2D Coverage;

layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;

void main() {
    vec4 scene = texture(SceneColor, inUv);
    float packCoverage = texture(Coverage, inUv).r;

    // The HDR source stores useful sky RGB with a low alpha value.  Source
    // alpha is therefore not a reliable copy factor for uncovered pixels.
    // Copy those pixels fully.  Where pack geometry already exists, ignore
    // low-alpha background RGB and keep the pack target; opaque host pixels
    // still overlay it through the fixed source-alpha blend state.
    if (packCoverage == -1.0) {
        scene.a = 1.0;
    } else if (scene.a < 0.5) {
        scene.a = 0.0;
    }
    outColor = scene;
}
