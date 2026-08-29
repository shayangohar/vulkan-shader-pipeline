#version 120e
/* RENDERTARGETS: 0 */
varying vec2 texcoord;
uniform sampler2D colortex0;

void main() {
    vec4 color = texture2D(colortex0, texcoord);
    gl_FragColor = vec4(color.rgb * vec3(1.0, 0.45, 0.9), color.a);
}
