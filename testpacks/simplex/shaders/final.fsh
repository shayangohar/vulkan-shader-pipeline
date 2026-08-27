#version 120e
varying vec2 texcoord;
uniform sampler2D colortex0;
void main() {
    vec4 color = texture2D(colortex0, texcoord);
    gl_FragColor = vec4(pow(color.rgb, vec3(1.0 / 1.2)), color.a);
}