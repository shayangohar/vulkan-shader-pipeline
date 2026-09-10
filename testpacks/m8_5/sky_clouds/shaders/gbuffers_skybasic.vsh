#version 120
varying vec4 skyColor;
void main() {
    skyColor = vec4(0.22, 0.48, 1.0, 1.0);
    gl_Position = ftransform();
}
