#version 330 compatibility

in vec3 vaPosition;
in vec4 vaColor;
in vec2 vaUV0;
in vec2 vaUV2;
in vec3 vaNormal;
in vec2 mc_Entity;
in vec2 mc_midTexCoord;
in vec3 at_midBlock;
in vec4 at_tangent;

out vec4 terrainColor;
out vec2 terrainUv;

void main() {
    gl_Position = vec4(vaPosition, 1.0);
    terrainColor = vaColor + vec4(mc_Entity.x * 0.0);
    terrainUv = vaUV0 + mc_midTexCoord * 0.0;
}
