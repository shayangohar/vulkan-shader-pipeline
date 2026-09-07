#version 120
uniform sampler2D colortex0;
uniform sampler2D colortex3;
varying vec2 texcoord;

void main() {
    gl_FragColor = texture2D(colortex3, texcoord);
}
