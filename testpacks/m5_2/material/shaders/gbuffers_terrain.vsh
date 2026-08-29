#version 120
attribute vec2 mc_Entity;

varying vec4 color;
varying vec2 texcoord;
varying vec2 lightcoord;
varying float materialId;

void main() {
    gl_Position = ftransform();
    color = gl_Color;
    texcoord = gl_MultiTexCoord0.xy;
    lightcoord = gl_MultiTexCoord1.xy;
    materialId = mc_Entity.x;
}
