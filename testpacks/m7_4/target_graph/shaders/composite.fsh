#version 120
uniform sampler2D colortex0;
uniform sampler2D depthtex0;
varying vec2 texcoord;

void main() {
    vec4 scene = texture2D(colortex0, texcoord);
    float depth = texture2D(depthtex0, texcoord).r;
    gl_FragColor = vec4(scene.rgb + vec3(depth * 0.00001), scene.a);
}
/*
const int colortex1Format = RGBA8;
const bool colortex1Clear = true;
const vec4 colortex1ClearColor = vec4(0.0, 0.0, 0.0, 0.0);
const bool colortex2Clear = false;
*/
