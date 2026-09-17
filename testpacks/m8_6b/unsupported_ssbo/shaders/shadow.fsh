#version 120
layout(std430, binding = 0) buffer MissingBuffer {
    uint values[];
} missingBuffer;

void main() {
    gl_FragColor = vec4(float(missingBuffer.values[0]));
}
