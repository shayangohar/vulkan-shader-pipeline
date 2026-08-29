#version 120e
#define DRAWBUFFERS1
varying vec4 color;
varying vec2 texcoord;
uniform sampler2D texture;

void main() {
    vec4 albedo = texture2D(texture, texcoord);
    gl_FragColor = vec4(albedo.rgb * color.rgb * vec3(0.35, 0.95, 0.75), albedo.a);
}
