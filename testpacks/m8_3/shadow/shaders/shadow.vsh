#version 400 compatibility

in vec3 vaPosition;
out vec2 shadowUv;

void main() {
    gl_Position = MVP * vec4(vaPosition, 1.0);
    shadowUv = vec2(0.5);
}
