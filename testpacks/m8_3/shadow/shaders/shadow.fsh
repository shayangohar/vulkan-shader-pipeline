#version 400 compatibility

in vec2 shadowUv;
uniform float shadowFade;

void main() {
    gl_FragColor = vec4(shadowUv, shadowFade, 1.0);
}
/*
const int shadowMapResolution = 1024;
const float shadowDistance = 192;
const float shadowDistanceRenderMul = 1.25;
const float sunPathRotation = 35;
const float sunPathOffset = 4;
*/
