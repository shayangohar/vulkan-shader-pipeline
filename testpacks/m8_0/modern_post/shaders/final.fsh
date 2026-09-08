#version 120
uniform sampler2D colortex0;
void main() {
    gl_FragColor = texture2D(colortex0, vec2(0.5));
}
