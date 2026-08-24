#version 460
// Fullscreen triangle generator (no vertex buffers - CustomVertexFormat.NONE).
// Y-inverted, matching the host pipeline's inverted-viewport convention: the
// world is rendered under a negative-height viewport, so sampling must flip V
// to stay upright through the composite and present chain.

layout(location = 0) out vec2 outUv;

void main() {
    vec2 uv = vec2(float((gl_VertexIndex << 1) & 2), -(float(gl_VertexIndex & 2)) + 1.0);
    outUv = uv;
    gl_Position = vec4(uv * vec2(2.0, -2.0) + vec2(-1.0, 1.0), 0.0, 1.0);
}
