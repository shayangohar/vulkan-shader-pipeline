#version 120

attribute float entityId;
attribute vec2 mc_midTexCoord;
attribute vec4 at_tangent;
varying vec4 entityColor;
varying vec2 entityUv;
varying float entityMarker;

void main() {
    gl_Position = ftransform();
    entityColor = gl_Color;
    entityUv = gl_MultiTexCoord0.xy;
    entityMarker = entityId > 0.0 ? 1.0 : 0.0;
}
