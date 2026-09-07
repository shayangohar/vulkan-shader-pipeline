#version 120
uniform sampler2D texture;
uniform sampler2D lightmap;
varying vec4 familyColor;
varying vec2 familyUv;
void main() {
    vec4 base = texture2D(texture, familyUv);
    gl_FragColor = vec4(base.rgb * familyColor.rgb
            * texture2D(lightmap, vec2(0.5)).rgb, base.a * familyColor.a);
}
