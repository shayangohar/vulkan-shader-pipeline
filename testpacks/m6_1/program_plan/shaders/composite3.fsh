#version 120

uniform sampler2D colortex0;
varying vec3 unsupportedUv;

void main() {
    gl_FragColor = texture2D(colortex0, unsupportedUv.xy);
}
