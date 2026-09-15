#version 430 compatibility
layout(local_size_x = 0, local_size_y = 0, local_size_z = 0) in;
layout(r16ui, binding = 0) uniform uimage3D missing_sampler;
void main() { }
