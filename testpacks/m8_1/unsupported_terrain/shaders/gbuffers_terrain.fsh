#version 450 core
layout(location = 0) in vec4 terrainColor;
layout(location = 0) out vec4 firstColor;
layout(location = 1) out vec4 secondColor;
void main() {
    firstColor = terrainColor;
    secondColor = terrainColor;
}
