#version 120
uniform sampler2D texture;
uniform sampler2D lightmap;

varying vec4 color;
varying vec2 texcoord;
varying vec2 lightcoord;
varying float materialId;

void main() {
    vec4 atlas = texture2D(texture, texcoord);
    vec4 light = texture2D(lightmap, lightcoord);
    vec3 marker = materialId >= 0.0 ? vec3(1.0, 0.20, 0.08) : vec3(0.55, 0.55, 0.55);
    gl_FragColor = vec4(atlas.rgb * color.rgb * light.rgb
            * mix(vec3(1.0), marker, 0.45), atlas.a * color.a);
}
