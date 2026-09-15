#version 120
varying vec2 texCoord;
uniform sampler2D colortex0;

void main() {
    vec3 corner = vec3(step(0.5, texCoord.x), step(0.5, texCoord.y), 0.2);
    gl_FragColor = vec4(corner, 1.0) * texture2D(colortex0, texCoord);
}
