#version 430 compatibility
layout(local_size_x = 2, local_size_y = 2, local_size_z = 2) in;
layout(rgba16f) uniform image3D small_image;

void main() {
    ivec3 p = ivec3(gl_GlobalInvocationID);
    imageStore(small_image, p, vec4(0.25, 0.5, 0.75, 1.0));
}
