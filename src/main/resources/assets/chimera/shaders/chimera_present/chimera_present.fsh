#version 460
// Present stage: final framebuffer -> swapchain passthrough.

layout(binding = 0) uniform sampler2D FinalColor;

layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(FinalColor, inUv);
}
