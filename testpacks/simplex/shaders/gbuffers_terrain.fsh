#version 120e
// M4 part-2 geometry fixture: runs as gbuffers_terrain on chimera's fixed
// terrain vertex (outputs: color->0, texcoord->1, lightSpacePos->5).
// Declares pack consts + DRAWBUFFERS for the part-2 parser, samples the
// block atlas (texture -> registry slot 0) and the shadow map
// (shadowtex0 -> registry slot 5), and applies a horizontal warm/cool
// gradient so the geometry stage is unmistakable on screen.
const int colortex0Format = RGBA16F;
const int shadowMapResolution = 2048;
#define DRAWBUFFERS1
varying vec4 color;
varying vec2 texcoord;
uniform sampler2D texture;
uniform sampler2D shadowtex0;
void main() {
    vec4 albedo = texture2D(texture, texcoord);
    if (albedo.a < 0.1) discard; // atlas cutout (leaves etc.), host-side behavior
    float shadow = texture2D(shadowtex0, vec2(0.5, 0.5)).r; // machinery proof only, not correct shadow math
    // Screen-space ramp: gl_FragCoord.x sweeps 0..2560, so the left half of
    // the screen shifts cool (G up, R down), the right half warm (R up).
    // Albedo is floored so the ramp stays visible in shadowed/cave views.
    float gx = gl_FragCoord.x / 2560.0;
    vec3 base = color.rgb * max(albedo.rgb, vec3(0.35))
            * vec3(0.5 + 0.5 * gx, 1.0 - 0.5 * gx, 0.5);
    base *= 0.6 + 0.4 * shadow;
    gl_FragColor = vec4(base, 1.0);
}