#version 120

attribute float mc_Entity;
varying vec4 vertexColor;
varying vec2 texcoord;

void main() {
    gl_Position = ftransform();
    vertexColor = gl_Color;
    texcoord = gl_MultiTexCoord0.xy;
}
