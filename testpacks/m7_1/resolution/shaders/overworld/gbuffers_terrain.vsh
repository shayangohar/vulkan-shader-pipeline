#version 120
attribute vec2 mc_Entity;
varying vec4 color;

void main() {
    gl_Position = ftransform();
    color = gl_Color;
}
