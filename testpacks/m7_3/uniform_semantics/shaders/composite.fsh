#version 120e
const float wetnessHalflife = 300.0;
const float eyeBrightnessHalflife = 20.0;

uniform sampler2D colortex0;
uniform float m73Marker;
uniform float wetness;
uniform float frameTimeCounter;
uniform int worldTime;
uniform int worldDay;
uniform int dimension;
uniform int heightLimit;
uniform int seaLevel;
uniform float ambientLight;
uniform float temperature;
uniform vec3 cameraPosition;
uniform vec3 sunPosition;
uniform mat4 gbufferProjection;
varying vec2 texcoord;

void main() {
    vec4 scene = texture2D(colortex0, texcoord);
    float marker = m73Marker + wetness + frameTimeCounter
            + float(worldTime + worldDay + dimension + heightLimit + seaLevel)
            + ambientLight + temperature + cameraPosition.x
            + sunPosition.y + gbufferProjection[0][0];
    gl_FragColor = vec4(scene.rgb + vec3(marker * 0.00001, 0.0, 0.0), scene.a);
}
