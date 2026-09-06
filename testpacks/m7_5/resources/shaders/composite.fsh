#version 120
uniform sampler2D colortex0;
uniform sampler2D gaux1;
uniform sampler2D depthtex0;
uniform sampler2D shadowtex0;
uniform sampler2D noisetex;
uniform sampler2D marker;
varying vec2 texcoord;

void main() {
    vec4 scene = texture2D(colortex0, texcoord);
    vec3 aux = texture2D(gaux1, texcoord).rgb;
    float depth = texture2D(depthtex0, texcoord).r;
    float shadow = texture2D(shadowtex0, texcoord).r;
    float noise = texture2D(noisetex, texcoord).r;
    float custom = texture2D(marker, texcoord).r;
    gl_FragColor = vec4(scene.rgb + aux * 0.01 + vec3(depth + shadow + noise + custom) * 0.01,
            scene.a);
}
