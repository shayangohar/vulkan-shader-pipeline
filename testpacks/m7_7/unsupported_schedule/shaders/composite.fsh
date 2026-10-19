#version 120
uniform sampler2D colortex0;
uniform sampler3D mysterySampler;
varying vec2 texcoord;

void main() {
    gl_FragColor = texture3D(mysterySampler, vec3(texcoord, 0.0));
}
