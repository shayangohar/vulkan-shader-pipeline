#version 120
uniform sampler2D texture;
varying vec4 skyColor;
varying vec2 skyUv;
void main() {
    gl_FragColor = skyColor * texture2D(texture, skyUv);
}
