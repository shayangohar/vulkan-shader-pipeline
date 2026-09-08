#version 400 compatibility
layout(location = 0) out vec4 fragColor;
uniform sampler2D colortex0;
void main() {
    fragColor = textureLod(colortex0, vec2(0.5), 0.0);
}
