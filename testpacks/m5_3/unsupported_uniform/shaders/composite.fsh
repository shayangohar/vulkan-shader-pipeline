#version 120e
uniform sampler2D colortex0;
uniform sampler2D unknownTexture;
uniform bool unsupportedToggle;
varying vec2 texcoord;

void main() {
    gl_FragColor = texture2D(colortex0, texcoord);
}
