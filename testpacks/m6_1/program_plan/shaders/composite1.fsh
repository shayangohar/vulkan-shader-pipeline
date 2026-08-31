#version 130
#extension GL_ARB_shader_texture_lod : enable

uniform sampler2D colortex0;
in vec2 texcoord;

void main() {
    vec4 nested = texture2D(colortex0, vec2(texture2D(colortex0, texcoord).r));
    gl_FragData[0] = texture2DLod(colortex0, texcoord, 0.0) + nested * 0.0;
}
