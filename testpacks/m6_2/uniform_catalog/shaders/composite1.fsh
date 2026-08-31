#version 120
uniform sampler2D colortex0;
uniform float unsupportedCatalogUniform;
varying vec2 texcoord;

void main() {
    gl_FragColor = texture2D(colortex0, texcoord)
            + vec4(unsupportedCatalogUniform * 0.0);
}
