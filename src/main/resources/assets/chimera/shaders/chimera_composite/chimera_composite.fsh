#version 460
// World resolve: moves the GL-row-ordered world (HDR, pack targets or pack
// final) into the host-ordered output. The world passes store GL row order
// (ChimeraRasterOrientation, KNOW-414), so this is the frame's one flip.

layout(binding = 0) uniform sampler2D SceneColor;

layout(location = 0) in vec2 inUv;
layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(SceneColor, vec2(inUv.x, 1.0 - inUv.y));
}
