#version 120
const int colortex8Format = RGBA16F;
uniform sampler2D colortex8;
#define DRAWBUFFERS05
void main() {
    vec4 reflection = texture2D(colortex8, vec2(0.5));
    gl_FragData[0] = reflection;
    gl_FragData[1] = vec4(reflection.rgb, 1.0);
}
