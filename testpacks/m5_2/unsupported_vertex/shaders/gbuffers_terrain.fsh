#version 120
uniform sampler2D texture;
varying vec4 color;

void main() {
    gl_FragColor = texture2D(texture, vec2(0.5)) * color;
}
