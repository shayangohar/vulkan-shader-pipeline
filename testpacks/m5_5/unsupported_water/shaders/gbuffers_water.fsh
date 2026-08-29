#version 120

uniform sampler2D texture;
uniform sampler2D depthtex0;

varying vec4 color;
varying vec2 texcoord;

void main() {
    float depth = texture2D(depthtex0, texcoord).r;
    gl_FragColor = texture2D(texture, texcoord) * color + vec4(depth * 0.0);
}
