#version 120
uniform sampler2D texture;
uniform sampler2D shadowtex1;
varying vec2 texcoord;

void main() {
    vec4 atlas = texture2D(texture, texcoord);
    float unused = texture2D(shadowtex1, vec2(0.5)).r;
    gl_FragColor = vec4(atlas.rgb + unused * 0.0, 1.0);
}
