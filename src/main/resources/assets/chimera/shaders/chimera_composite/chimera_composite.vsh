#version 460
// Fullscreen triangle generator (no vertex buffers - CustomVertexFormat.NONE).
// Covers the viewport with UVs spanning 0..2; offscreen regions are clipped.

layout(location = 0) out vec2 outUv;

void main() {
    vec2 pos = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    outUv = pos;
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
