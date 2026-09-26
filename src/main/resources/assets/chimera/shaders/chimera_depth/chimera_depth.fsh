#version 460
layout(binding = 0) uniform sampler2D SceneDepth;
layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outDepth;

void main() {
    // The CHIMERA world depth attachment uses forward [0,1] depth. Keep that
    // value unchanged; pack projection uniforms perform the clip-range mapping.
    float depth = texture(SceneDepth, inUv).r;
    outDepth = vec4(depth, 0.0, 0.0, 1.0);
}
