#version 430 compatibility
#define COMPUTE_SHADER
layout(local_size_x = 2, local_size_y = 2, local_size_z = 2) in;
layout(r8ui, binding = 0) uniform uimage3D voxel_sampler;
layout(rgba16f, binding = 1) uniform image3D light_sampler;
void main() {
    ivec3 p = ivec3(gl_GlobalInvocationID.xyz);
    imageStore(voxel_sampler, p, uvec4(1u));
    imageStore(light_sampler, p, vec4(1.0));
}
