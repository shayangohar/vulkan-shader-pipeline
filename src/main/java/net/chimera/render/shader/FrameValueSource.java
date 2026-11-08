package net.chimera.render.shader;

import net.vulkanmod.vulkan.util.MappedBuffer;

import java.util.Set;

/** A group of frame values one class captures, served by uniform name beside PackFrameState's table. */
interface FrameValueSource {
    /** The uniform names this source serves. */
    Set<String> names();

    /** The scalar, or one component of a vector (-1 for a scalar), served under a name; NaN past its width. */
    double value(String name, int component);

    /** Writes a name's value into its buffer slot, in the type the pack declared it. */
    void write(String name, String type, MappedBuffer target);
}
