#version 120e
/* RENDERTARGETS: 0,1 */
varying vec4 color;
varying vec2 texcoord;
uniform sampler2D texture;

void main() {
    vec4 albedo = texture2D(texture, texcoord);
    gl_FragData[0] = vec4(albedo.rgb * color.rgb, 1.0);
    gl_FragData[1] = vec4(1.0, 0.0, 1.0, 1.0);
}
