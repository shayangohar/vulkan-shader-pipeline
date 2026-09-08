#version 400 compatibility

in vec2 shadowUv;
uniform float shadowFade;

void main() {
    gl_FragColor = vec4(shadowUv, shadowFade, 1.0);
}
