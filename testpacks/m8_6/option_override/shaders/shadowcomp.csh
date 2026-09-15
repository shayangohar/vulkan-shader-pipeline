#version 430 compatibility
#define COMPUTE_SHADER
#define SHADOWCOMP

#ifdef ENABLE_ADVANCED
layout(local_size_x = 2, local_size_y = 2, local_size_z = 2) in;
writeonly uniform image3D test_image;

void main() {
    imageStore(test_image, ivec3(gl_GlobalInvocationID), vec4(1.0));
}
#endif
