#version 120

#define M61_FEATURE 1
#define M61_FEATURE 2
#if defined(M61_FEATURE) && (M61_FEATURE == 2)
#include "/lib/common.glsl"
#else
vec4 m61Marker(vec4 color) {
    return color;
}
#endif

uniform sampler2D colortex0;
uniform float frameTimeCounter;
noperspective varying vec2 texcoord;

void main() {
    vec4 color = texture2D(colortex0, texcoord);
    gl_FragColor = m61Marker(color + vec4(frameTimeCounter * 0.0));
}
