#version 120
attribute float entityId;
attribute vec2 mc_midTexCoord;
attribute vec4 at_tangent;
varying vec4 familyColor;
varying vec2 familyUv;
void main() {
    gl_Position = ftransform();
    familyColor = gl_Color;
    familyUv = gl_MultiTexCoord0.xy;
}
