#version 120

uniform sampler2D texture;
uniform sampler3D volume;

varying vec4 color;
varying vec2 texcoord;

void main() {
    float density = texture3D(volume, vec3(texcoord, 0.5)).r;
    gl_FragColor = texture2D(texture, texcoord) * color + vec4(density * 0.0);
}
