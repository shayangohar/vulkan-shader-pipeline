#version 120
uniform sampler2D texture;
uniform sampler2D lightmap;
uniform sampler2D normals;
uniform sampler2D specular;

varying vec4 color;
varying vec2 texcoord;
varying vec2 lightcoord;
varying float materialId;

void main() {
    vec4 atlas = texture2D(texture, texcoord);
    vec4 light = texture2D(lightmap, lightcoord);
    vec3 normalSample = texture2D(normals, texcoord).rgb;
    float smoothness = texture2D(specular, texcoord).r;
    vec3 marker = materialId >= 0.0 ? vec3(1.0, 0.20, 0.08) : vec3(0.55, 0.55, 0.55);
    gl_FragColor = vec4(atlas.rgb * color.rgb * light.rgb * normalSample * smoothness
            * mix(vec3(1.0), marker, 0.45), atlas.a * color.a);
}
