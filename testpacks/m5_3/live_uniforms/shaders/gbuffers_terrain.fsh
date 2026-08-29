#version 120
uniform sampler2D texture;
varying vec4 color;
varying vec2 texcoord;

void main() {
    gl_FragColor = texture2D(texture, texcoord) * color;
}
