#version 120
uniform sampler2D colortex0;
varying vec2 texcoord;

void main() {
    gl_FragColor = texture2D(colortex0, texcoord);
}
/*
const int colortex1Format = RGBA8;
const bool colortex1Clear = true;
const vec4 colortex1ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
*/
