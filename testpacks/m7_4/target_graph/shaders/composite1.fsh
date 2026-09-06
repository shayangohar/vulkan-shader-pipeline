#version 120
/* RENDERTARGETS: 1 */
uniform sampler2D colortex0;
varying vec2 texcoord;

void main() {
    vec3 source = texture2D(colortex0, texcoord).rgb;
    gl_FragColor = vec4(source.b, source.r, 0.25, 1.0);
}
