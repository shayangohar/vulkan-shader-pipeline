#version 120
layout(std430, binding = 0) buffer blockDataBuffer {
    uint values[];
} blockDataBuffer;

void main() {
    gl_Position = ftransform();
}
