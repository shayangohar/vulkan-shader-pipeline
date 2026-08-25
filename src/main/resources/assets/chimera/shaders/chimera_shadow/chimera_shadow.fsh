#version 460
// Shadow pass fragment: outputs the depth for shadow map sampling.
// The terrain shader reads this texture and compares depths.

layout(location = 0) out vec4 outColor;

void main() {
    outColor = vec4(gl_FragCoord.z, 0.0, 0.0, 1.0);
}
