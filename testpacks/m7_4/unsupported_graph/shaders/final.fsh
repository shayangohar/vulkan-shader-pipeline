#version 120
/* RENDERTARGETS: 0,1 */
uniform sampler2D colortex0;
varying vec2 texcoord;

void main() {
    gl_FragData[0] = texture2D(colortex0, texcoord);
    gl_FragData[1] = vec4(0.0);
}
