#version 120
#if QUALITY >= 2
#include "../lib/common.glsl"
#endif
varying vec4 color;

void main() {
    gl_FragColor = color;
}
