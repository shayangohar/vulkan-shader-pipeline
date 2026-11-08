#version 120
uniform sampler2D texture;
uniform sampler3D volume;
varying vec2 texcoord;

void main() {
    vec4 atlas = texture2D(texture, texcoord);
    float unused = texture3D(volume, vec3(0.5)).r;
    gl_FragColor = vec4(atlas.rgb + unused * 0.0, 1.0);
}
