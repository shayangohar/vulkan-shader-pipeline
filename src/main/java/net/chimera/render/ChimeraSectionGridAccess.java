package net.chimera.render;

import net.vulkanmod.render.chunk.SectionGrid;

/** VulkanMod's section grid, which the shadow pass culls against the light. */
public interface ChimeraSectionGridAccess {
    SectionGrid chimera$sectionGrid();
}
