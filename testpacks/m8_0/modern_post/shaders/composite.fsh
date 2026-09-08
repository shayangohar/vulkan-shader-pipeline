#version 330 core
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;
uniform sampler2D colortex0;
const mat3 packTransform = mat3(1.0);
void main() {
    vec4 first = texture(colortex0, texCoord);
    fragColor = vec4((packTransform * first.rgb), 1.0);
}
