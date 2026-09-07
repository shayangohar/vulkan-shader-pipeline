#version 120
uniform sampler2D colortex0;
uniform sampler2D depthtex1;
varying vec2 texcoord;

void main() {
    vec4 scene = texture2D(colortex0, texcoord);
    float depth = texture2D(depthtex1, texcoord).r;
    gl_FragColor = vec4(scene.rgb + vec3(depth * 0.00001), scene.a);
}
