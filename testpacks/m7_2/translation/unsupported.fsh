#version 120
uniform sampler2D shadowtex0;
void main() {
    gl_FragColor = shadow2DProj(shadowtex0, vec4(0.0));
}
