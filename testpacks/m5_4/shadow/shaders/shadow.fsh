#version 120
uniform sampler2D texture;
uniform sampler2D lightmap;

varying vec4 color;
varying vec2 texcoord;
varying vec2 lightcoord;

void main() {
    vec4 atlas = texture2D(texture, texcoord);
    vec4 light = texture2D(lightmap, lightcoord);
    if (atlas.a * color.a < 0.1) {
        discard;
    }
    gl_FragColor = vec4(gl_FragCoord.z, light.r, 0.0, 1.0);
}
