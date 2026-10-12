#version 120
uniform sampler2D colortex0;
uniform float fogDensity;
varying vec2 texcoord;

void main() {
    gl_FragColor = texture2D(colortex0, texcoord)
            + vec4(fogDensity * 0.0);
}
