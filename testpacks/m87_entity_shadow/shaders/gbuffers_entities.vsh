#version 120
attribute vec4 mc_Entity;

varying vec4 color;
varying vec2 texcoord;
varying vec2 lightcoord;

void main() {
    gl_Position = ftransform();
    color = gl_Color;
    texcoord = gl_MultiTexCoord0.xy;
    lightcoord = gl_MultiTexCoord1.xy;
}
