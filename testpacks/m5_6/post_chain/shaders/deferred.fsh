#version 120

uniform sampler2D colortex0;
varying vec2 texcoord;
/* RENDERTARGETS: 0,1 */

void main() {
    vec4 world = texture2D(colortex0, texcoord);
    gl_FragData[0] = world;
    gl_FragData[1] = vec4(0.95, 0.10, 0.75, 1.0);
}
