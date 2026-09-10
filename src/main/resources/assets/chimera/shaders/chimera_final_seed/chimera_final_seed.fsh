#version 460

layout(binding = 0) uniform sampler2D SceneColor;

layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(SceneColor, inUv);
}
