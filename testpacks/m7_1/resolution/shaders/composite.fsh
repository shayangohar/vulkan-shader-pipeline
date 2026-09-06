#version 120
#include "/lib/common.glsl"
uniform sampler2D colortex0;
varying vec2 texcoord;

void main() {
    gl_FragColor = m71Marker(texture2D(colortex0, texcoord));
}
