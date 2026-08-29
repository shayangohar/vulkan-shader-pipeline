#version 120

attribute vec2 mc_Entity;
varying vec4 color;
varying vec2 texcoord;

void main() {
    gl_Position = ftransform();
    color = gl_Color;
    texcoord = gl_MultiTexCoord0.xy;
}
