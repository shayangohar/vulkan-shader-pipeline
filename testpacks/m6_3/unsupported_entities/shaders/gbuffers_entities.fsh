#version 120

uniform sampler2D unknownEntityTexture;
varying vec2 entityUv;

void main() {
    gl_FragColor = texture2D(unknownEntityTexture, entityUv);
}
