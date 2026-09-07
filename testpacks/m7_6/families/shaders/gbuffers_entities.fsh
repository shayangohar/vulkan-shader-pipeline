#version 120
uniform sampler2D texture;
uniform sampler2D lightmap;
varying vec4 familyColor;
varying vec2 familyUv;
void main() {
    gl_FragColor = texture2D(texture, familyUv) * familyColor
            * texture2D(lightmap, vec2(0.5));
}
