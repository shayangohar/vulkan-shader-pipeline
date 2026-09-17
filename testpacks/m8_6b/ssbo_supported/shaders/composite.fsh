#version 120
layout(std430, binding = 1) buffer UnnamedBlock {
    float values[];
};

void main() {
    gl_FragColor = vec4(values[0], 0.0, 0.0, 1.0);
}
