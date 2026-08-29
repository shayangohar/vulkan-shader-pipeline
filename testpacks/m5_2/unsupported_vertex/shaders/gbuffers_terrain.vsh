#version 120
attribute float mc_Entity;
varying vec4 color;

void main() {
    gl_Position = ftransform();
    color = gl_Color;
    float unsupportedNormal = gl_Normal.x;
    color.rgb += unsupportedNormal * 0.0;
}
