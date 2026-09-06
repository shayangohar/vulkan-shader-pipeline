#version 130
#extension GL_ARB_shader_texture_lod : enable
uniform mat4 gbufferProjection, gbufferProjectionInverse;
uniform sampler2D colortex0;
uniform sampler2D shadowtex0;
noperspective varying vec2 texCoord;
void main() {
    vec4 base = texture2D(colortex0, texCoord + texture2D(colortex0, texCoord).xy);
    vec4 shadow = shadow2D(shadowtex0, vec3(texCoord, 0.5));
    gl_FragColor = base * shadow + vec4(gbufferProjectionInverse[0].x);
}
