#version 120e
#define M62_FEATURE 1
#define M62_FEATURE 2
#if defined(M62_FEATURE) && (M62_FEATURE == 2)
#include "/lib/marker.glsl"
#endif

uniform sampler2D colortex0;
uniform vec3 cameraPosition;
uniform int worldTime;
uniform int worldDay;
uniform float frameTimeCounter;
uniform float rainStrength;
uniform float thunderStrength;
uniform float wetness;
uniform float nightVision;
uniform ivec2 eyeBrightness;
uniform ivec2 eyeBrightnessSmooth;
uniform vec3 playerLookVector;
uniform mat4 gbufferModelView;
varying vec2 texcoord;

void main() {
    vec4 scene = texture2D(colortex0, texcoord);
    vec3 marker = m62CatalogMarker(cameraPosition,
            wetness + rainStrength + thunderStrength + nightVision,
            frameTimeCounter + float(worldTime + worldDay)
                    + float(eyeBrightness.x + eyeBrightnessSmooth.y)
                    + playerLookVector.x + gbufferModelView[0][0]);
    gl_FragColor = vec4(scene.rgb + marker, scene.a);
}
