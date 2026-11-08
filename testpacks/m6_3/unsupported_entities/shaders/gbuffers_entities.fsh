#version 120

uniform sampler2D texture;
uniform sampler3D unknownEntityTexture;
varying vec2 entityUv;

void main() {
    gl_FragColor = texture2D(texture, entityUv) * texture3D(unknownEntityTexture, vec3(entityUv, 0.0));
}
