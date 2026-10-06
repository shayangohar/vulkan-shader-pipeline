#version 460
layout(location = 0) out vec2 outUv;

// The fullscreen quad shared with chimera_composite.vsh.
const vec2 CHIMERA_QUAD[6] = vec2[](vec2(0.0, 0.0), vec2(1.0, 1.0), vec2(1.0, 0.0),
                                    vec2(0.0, 0.0), vec2(0.0, 1.0), vec2(1.0, 1.0));

void main() {
    vec2 uv = CHIMERA_QUAD[gl_VertexIndex];
    outUv = uv;
    gl_Position = vec4(uv * vec2(2.0, -2.0) + vec2(-1.0, 1.0), 0.0, 1.0);
}
