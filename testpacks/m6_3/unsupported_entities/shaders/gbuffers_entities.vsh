#version 120

varying vec2 entityUv;

void main() {
    gl_Position = ftransform();
    entityUv = gl_MultiTexCoord0.xy;
}
