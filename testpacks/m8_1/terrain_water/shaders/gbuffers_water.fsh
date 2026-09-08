#version 330 compatibility

uniform sampler2D texture;
in vec4 waterColor;
in vec2 waterUv;
out vec4 fragColor;

void main() {
    fragColor = texture2D(texture, waterUv) * waterColor;
}
