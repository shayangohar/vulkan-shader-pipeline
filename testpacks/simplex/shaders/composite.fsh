#version 120e
varying vec2 texcoord;
uniform sampler2D colortex0;
uniform sampler2D shadowtex0;
void main() {
    vec4 color = texture2D(colortex0, texcoord);
    float lit = 0.6 + 0.4 * texture2D(shadowtex0, texcoord).r;
    gl_FragColor = vec4(color.rgb * vec3(1.0, 0.3, 0.3) * lit, color.a);
}