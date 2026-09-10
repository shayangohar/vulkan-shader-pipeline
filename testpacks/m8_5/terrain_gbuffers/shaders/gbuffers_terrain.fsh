#version 130

uniform sampler2D texture;
uniform sampler2D lightmap;

in vec4 color;
in vec2 texCoord;
in vec2 lightCoord;
flat in int material;

void main() {
    vec4 atlas = texture2D(texture, texCoord);
    vec4 light = texture2D(lightmap, lightCoord);
    vec3 marker = material < 0 ? vec3(0.15, 0.35, 0.95) : vec3(0.95, 0.45, 0.10);
    vec4 lit = vec4(atlas.rgb * color.rgb * light.rgb, atlas.a * color.a);
    /* DRAWBUFFERS:06 */
    gl_FragData[0] = mix(lit, vec4(marker, lit.a), 0.35);
    gl_FragData[1] = vec4(marker, 1.0);
}
