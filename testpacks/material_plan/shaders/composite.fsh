#version 120
uniform sampler2D colortex0;
uniform sampler2D normals;
uniform sampler2D specular;
varying vec2 texcoord;

void main() {
    vec4 scene = texture2D(colortex0, texcoord);
    vec3 normalSample = texture2D(normals, texcoord).rgb;
    float smoothness = texture2D(specular, texcoord).r;
    gl_FragColor = vec4(scene.rgb * normalSample * smoothness, scene.a);
}
