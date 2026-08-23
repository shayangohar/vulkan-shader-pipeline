#version 460
// Present-stage vertex: same fullscreen triangle as the composite stage.

layout(location = 0) out vec2 outUv;

void main() {
    vec2 pos = vec2(float((gl_VertexIndex << 1) & 2), float(gl_VertexIndex & 2));
    outUv = pos;
    gl_Position = vec4(pos * 2.0 - 1.0, 0.0, 1.0);
}
