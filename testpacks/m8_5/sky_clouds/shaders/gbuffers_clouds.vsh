#version 120
varying vec4 cloudColor;
void main() {
    cloudColor = gl_Color;
    gl_Position = ftransform();
}
