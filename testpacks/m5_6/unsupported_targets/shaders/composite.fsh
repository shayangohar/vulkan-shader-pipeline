#version 120

uniform sampler2D colortex0;
varying vec2 texcoord;
/* RENDERTARGETS: 0,8 */

void main() {
    gl_FragData[0] = texture2D(colortex0, texcoord);
    gl_FragData[1] = vec4(1.0, 0.0, 1.0, 1.0);
}
