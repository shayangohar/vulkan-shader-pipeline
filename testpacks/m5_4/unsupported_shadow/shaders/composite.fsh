#version 120
uniform sampler2D colortex0;
varying vec2 texcoord;

void main() {
    gl_FragColor = texture2D(colortex0, texcoord);
}
/*
const int shadowMapResolution = 0;
const float shadowDistance = NaN;
*/
