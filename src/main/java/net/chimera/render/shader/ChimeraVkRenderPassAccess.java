package net.chimera.render.shader;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

/** Narrow access to host per-draw values needed by the entity bridge. */
public interface ChimeraVkRenderPassAccess {
    GpuBufferSlice chimera$uniform(String name);
}
