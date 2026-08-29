#version 120

uniform sampler2D texture;
uniform sampler2D lightmap;
uniform sampler2D shadowtex0;
uniform vec4 fogColor;
uniform float fogStart;
uniform float fogEnd;
uniform ivec2 textureSize;
uniform vec2 texelSize;

varying vec4 color;
varying vec2 texcoord;
varying vec2 lightcoord;
varying float renderType;

void main() {
    vec4 atlas = texture2D(texture, texcoord);
    vec4 light = texture2D(lightmap, lightcoord);
    float shadow = texture2D(shadowtex0, vec2(0.5)).r;
    float fogAmount = clamp((fogEnd - fogStart) / max(fogEnd, 1.0), 0.0, 1.0);
    float sizeMarker = clamp(float(textureSize.x) * texelSize.x, 0.0, 1.0);
    vec3 marker = mix(vec3(0.05, 0.25, 0.75), vec3(0.05, 0.90, 1.0),
            clamp(renderType, 0.0, 1.0));
    vec3 lit = atlas.rgb * color.rgb * light.rgb;
    vec3 outputColor = mix(lit, marker, 0.75);
    outputColor = mix(outputColor, fogColor.rgb, 0.10 * fogAmount);
    gl_FragColor = vec4(outputColor, 0.60 + 0.10 * shadow + 0.05 * sizeMarker);
}
