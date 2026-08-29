#version 120e
/* RENDERTARGETS: 0 */
varying vec2 texcoord;
uniform sampler2D colortex0;

void main() {
    vec4 color = texture2D(colortex0, texcoord);
    gl_FragColor = vec4(color.rgb * vec3(0.35, 0.85, 1.0), color.a);
}
