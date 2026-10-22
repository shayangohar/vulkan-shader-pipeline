#version 120

uniform sampler3D unknownEntityTexture;
varying vec2 entityUv;

void main() {
    gl_FragColor = texture3D(unknownEntityTexture, vec3(entityUv, 0.0));
}
