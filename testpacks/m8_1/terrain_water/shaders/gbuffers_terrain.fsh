#version 330 compatibility

uniform sampler2D texture;
in vec4 terrainColor;
in vec2 terrainUv;
out vec4 fragColor;

void main() {
    fragColor = texture2D(texture, terrainUv) * terrainColor;
}
