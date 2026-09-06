#version 460
layout(binding = 0) uniform sampler2D SceneDepth;
layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outDepth;

void main() {
    // VulkanMod uses reversed Z. Iris/OptiFine depth samplers expose the
    // conventional value, so convert while copying into R32_SFLOAT.
    float depth = texture(SceneDepth, inUv).r;
    outDepth = vec4(1.0 - depth, 0.0, 0.0, 1.0);
}
