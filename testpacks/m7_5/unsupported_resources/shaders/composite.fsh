#version 120
uniform sampler2D missing;
uniform sampler2D unsafe;
varying vec2 texcoord;

void main() {
    gl_FragColor = texture2D(missing, texcoord) + texture2D(unsafe, texcoord);
}
