#version 120
varying vec4 particleColor;
varying vec2 particleUv;
void main() {
    gl_Position = ftransform();
    particleColor = gl_Color;
    particleUv = gl_MultiTexCoord0.xy;
}
