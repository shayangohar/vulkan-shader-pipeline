#version 120
uniform sampler2D texture;
uniform sampler2D lightmap;
uniform sampler2D normals;
uniform sampler2D specular;
uniform sampler2D shadowtex0;
uniform sampler2D shadowtex1;
uniform sampler2D shadowcolor0;

varying vec4 color;
varying vec2 texcoord;
varying vec2 lightcoord;

void main() {
    vec4 albedo = texture2D(texture, texcoord);
    vec4 light = texture2D(lightmap, lightcoord);
    vec3 normalSample = texture2D(normals, texcoord).rgb;
    float smoothness = texture2D(specular, texcoord).r;
    float shadow0 = texture2D(shadowtex0, texcoord).r;
    float shadow1 = texture2D(shadowtex1, texcoord).r;
    vec3 shadowColor = texture2D(shadowcolor0, texcoord).rgb;
    gl_FragColor = vec4(albedo.rgb * color.rgb * light.rgb * normalSample * smoothness
            * shadow0 * shadow1 * shadowColor, albedo.a * color.a);
}
