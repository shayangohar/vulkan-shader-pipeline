#version 460
// Present-stage vertex: same Y-inverted fullscreen triangle as the composite
// stage, matching the host pipeline's inverted-viewport convention.

layout(location = 0) out vec2 outUv;

void main() {
    vec2 uv = vec2(float((gl_VertexIndex << 1) & 2), -(float(gl_VertexIndex & 2)) + 1.0);
    outUv = uv;
    gl_Position = vec4(uv * vec2(2.0, -2.0) + vec2(-1.0, 1.0), 0.0, 1.0);
}
