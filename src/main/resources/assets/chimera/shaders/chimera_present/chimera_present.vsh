#version 460
// Present-stage vertex: use the same fullscreen coordinate contract as the
// composite stage. The host viewport owns the framebuffer orientation.

layout(location = 0) out vec2 outUv;

void main() {
    vec2 chimeraUv = vec2(float((gl_VertexIndex << 1) & 2), -(float(gl_VertexIndex & 2)) + 1.0);
    outUv = chimeraUv;
    gl_Position = vec4(chimeraUv * vec2(2.0, -2.0) + vec2(-1.0, 1.0), 0.0, 1.0);
}
