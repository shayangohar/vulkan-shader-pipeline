#version 120
uniform sampler2D texture;
varying vec4 particleColor;
varying vec2 particleUv;
void main() {
    gl_FragColor = texture2D(texture, particleUv) * particleColor;
}
