#version 120e
uniform sampler2D colortex0;
uniform sampler2D depthtex0;
uniform vec3 cameraPosition;
uniform vec4 fogColor;
uniform vec3 sunPosition;
uniform float frameTimeCounter;
uniform int worldTime;
uniform float viewWidth;
uniform float viewHeight;
uniform float wetness;
varying vec2 texcoord;

void main() {
    vec4 scene = texture2D(colortex0, texcoord);
    float depth = texture2D(depthtex0, texcoord).r;
    float sizeMarker = (viewWidth / max(viewHeight, 1.0)) * 0.02;
    float timeMarker = sin(frameTimeCounter + float(worldTime) * 0.001) * 0.04;
    float cameraMarker = cameraPosition.y * 0.0001 + sunPosition.y * 0.03;
    float marker = clamp(0.25 + sizeMarker + timeMarker + cameraMarker + depth * 0.1 + wetness, 0.0, 1.0);
    vec3 tint = mix(vec3(0.15, 0.45, 1.0), fogColor.rgb + sunPosition * 0.1, 0.25);
    gl_FragColor = vec4(mix(scene.rgb, tint, marker * 0.35), scene.a);
}
