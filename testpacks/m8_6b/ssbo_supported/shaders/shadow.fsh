#version 120
layout(std430, binding = 0) buffer blockDataBuffer {
    uint values[];
} blockDataBuffer;

void main() {
    float marker = float(blockDataBuffer.values[0]);
    gl_FragColor = vec4(marker, 0.0, 0.0, 1.0);
}
