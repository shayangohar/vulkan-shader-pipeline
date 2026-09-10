#version 120
varying vec4 skyColor;
varying vec2 skyUv;
void main() {
    skyColor = vec4(0.35, 0.75, 1.0, 1.0);
    skyUv = gl_MultiTexCoord0.st;
    gl_Position = ftransform();
}
