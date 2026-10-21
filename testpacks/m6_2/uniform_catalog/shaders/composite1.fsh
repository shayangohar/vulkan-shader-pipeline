#version 120
uniform sampler2D colortex0;
uniform float alphaTestRef;
varying vec2 texcoord;

void main() {
    gl_FragColor = texture2D(colortex0, texcoord)
            + vec4(alphaTestRef * 0.0);
}
