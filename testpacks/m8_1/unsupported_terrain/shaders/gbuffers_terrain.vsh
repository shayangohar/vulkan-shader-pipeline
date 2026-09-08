#version 450 core
layout(location = 0) in vec3 vaPosition;
layout(location = 0) out vec4 terrainColor;
void main() {
    gl_Position = vec4(vaPosition, 1.0);
    terrainColor = vec4(1.0);
}
