#version 120
uniform sampler2D colortex0;
uniform sampler2D shadowtex0;
uniform vec3 shadowLightPosition;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
uniform float frameTimeCounter;
uniform int worldTime;
varying vec2 texcoord;

void main() {
    vec4 scene = texture2D(colortex0, texcoord);
    float shadowDepth = texture2D(shadowtex0, vec2(0.5)).r;
    float matrixMarker = abs(shadowModelView[0][0]) * 0.03
            + abs(shadowProjection[1][1]) * 0.03;
    float timeMarker = sin(frameTimeCounter + float(worldTime) * 0.001) * 0.02;
    float lightMarker = abs(shadowLightPosition.y) * 0.08;
    float marker = clamp(0.30 + shadowDepth * 0.12 + matrixMarker
            + timeMarker + lightMarker, 0.0, 1.0);
    vec3 tint = vec3(0.08, 0.35, 1.0);
    gl_FragColor = vec4(mix(scene.rgb, tint, marker * 0.55), scene.a);
}
