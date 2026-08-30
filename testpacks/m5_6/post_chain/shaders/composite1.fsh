#version 120

uniform sampler2D colortex0;
uniform sampler2D colortex1;
varying vec2 texcoord;
/* RENDERTARGETS: 0 */

void main() {
    vec4 world = texture2D(colortex0, texcoord);
    vec4 marker = texture2D(colortex1, texcoord);
    gl_FragColor = mix(world, marker, 0.35);
}
