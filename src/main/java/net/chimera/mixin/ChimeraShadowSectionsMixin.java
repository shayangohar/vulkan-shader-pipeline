package net.chimera.mixin;

import net.chimera.render.ChimeraSectionGridAccess;
import net.chimera.render.ShadowSectionQueue;
import net.vulkanmod.render.chunk.ChunkArea;
import net.vulkanmod.render.chunk.RenderSection;
import net.vulkanmod.render.chunk.SectionGrid;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.graph.SectionGraph;
import net.vulkanmod.render.chunk.util.AreaSetQueue;
import net.vulkanmod.render.chunk.util.StaticQueue;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets the shadow pass draw the sections {@link ShadowSectionQueue} chose
 * from the light's view, through VulkanMod's ordinary section drawing. Off
 * the shadow pass both reads return the camera queues unchanged.
 */
@Mixin(value = WorldRenderer.class, remap = false)
public abstract class ChimeraShadowSectionsMixin implements ChimeraSectionGridAccess {
    @Shadow
    private SectionGrid sectionGrid;

    @Redirect(method = "renderSectionLayer", at = @At(value = "INVOKE",
            target = "Lnet/vulkanmod/render/chunk/graph/SectionGraph;getChunkAreaQueue()Lnet/vulkanmod/render/chunk/util/AreaSetQueue;"),
            require = 1)
    private AreaSetQueue chimera$shadowAreas(SectionGraph graph) {
        return ShadowSectionQueue.active() ? ShadowSectionQueue.areas() : graph.getChunkAreaQueue();
    }

    @Redirect(method = "renderSectionLayer", at = @At(value = "FIELD",
            target = "Lnet/vulkanmod/render/chunk/ChunkArea;sectionQueue:Lnet/vulkanmod/render/chunk/util/StaticQueue;",
            opcode = Opcodes.GETFIELD), require = 1)
    private StaticQueue<RenderSection> chimera$shadowSections(ChunkArea area) {
        return ShadowSectionQueue.active() ? ShadowSectionQueue.sections(area) : area.sectionQueue;
    }

    @Override
    public SectionGrid chimera$sectionGrid() {
        return this.sectionGrid;
    }
}
