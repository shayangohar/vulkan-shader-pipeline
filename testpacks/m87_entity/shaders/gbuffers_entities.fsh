#version 120
uniform sampler2D texture;
uniform sampler2D lightmap;
uniform sampler2D normals;
uniform sampler2D specular;

varying vec4 color;
varying vec2 texcoord;
varying vec2 lightcoord;

void main() {
    vec4 albedo = texture2D(texture, texcoord);
    vec4 light = texture2D(lightmap, lightcoord);
    vec3 normalSample = texture2D(normals, texcoord).rgb;
    float smoothness = texture2D(specular, texcoord).r;
    gl_FragColor = vec4(albedo.rgb * color.rgb * light.rgb * normalSample * smoothness,
            albedo.a * color.a);
}
