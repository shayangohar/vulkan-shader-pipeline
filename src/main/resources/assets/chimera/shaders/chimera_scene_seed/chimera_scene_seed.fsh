#version 460

layout(binding = 0) uniform sampler2D SceneColor;
layout(binding = 1) uniform sampler2D Coverage;

layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;

void main() {
    // Discard preserves the pack geometry already present in target 0.
    if (texture(Coverage, inUv).r != -1.0) discard;
    outColor = texture(SceneColor, inUv);
}
