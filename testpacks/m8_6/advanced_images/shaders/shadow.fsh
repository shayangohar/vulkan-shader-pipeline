#version 120

uniform uimage3D voxel_sampler;

void main() {
    imageStore(voxel_sampler, ivec3(0, 0, 0), uvec4(1u));
    gl_FragColor = vec4(1.0);
}
