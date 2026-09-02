#version 120
uniform sampler2D colortex0;
varying vec2 texcoord;
/* RENDERTARGETS: 0,4 */
void main() {
    vec4 scene = texture2D(colortex0, texcoord);
    gl_FragData[0] = scene;
    gl_FragData[1] = vec4(0.0, 1.0, 1.0, 1.0);
}
