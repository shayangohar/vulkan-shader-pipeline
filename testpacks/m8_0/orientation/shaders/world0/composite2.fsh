#version 300 es
varying vec2 texCoord;
uniform sampler2D colortex0;

void main() {
    gl_FragColor = texture2D(colortex0, texCoord);
}
