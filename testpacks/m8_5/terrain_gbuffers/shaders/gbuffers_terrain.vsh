#version 130

attribute vec4 mc_Entity;
attribute vec4 mc_midTexCoord;
attribute vec4 at_tangent;

out vec4 color;
out vec2 texCoord;
out vec2 lightCoord;
flat out int material;

void main() {
    gl_Position = ftransform();
    color = gl_Color;
    texCoord = gl_MultiTexCoord0.xy;
    lightCoord = gl_MultiTexCoord1.xy;
    material = int(mc_Entity.x);
}
