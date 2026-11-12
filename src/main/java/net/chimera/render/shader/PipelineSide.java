package net.chimera.render.shader;

import net.chimera.shaderpack.PackBlendPlan;
import net.chimera.shaderpack.SpirvOutputWrites;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;

/**
 * What Chimera keeps on one VulkanMod GraphicsPipeline (ChimeraPipelineSideMixin): the attachment
 * contract its variants render into and its blends in VulkanMod's own render passes
 * (MrtPipelineContext), the output locations its fragment stage writes, the blend its draws report
 * as blendFunc (IrisDrawState), and its geometry stage (GeometryStageModules). It lives as long as
 * the pipeline does; the pipeline's cleanUp destroys the geometry module. A pack load writes it on
 * the render thread, before VulkanMod creates the pipeline's later variants there.
 */
public final class PipelineSide {
    /** A GraphicsPipeline, through ChimeraPipelineSideMixin. */
    public interface Holder {
        PipelineSide chimera$side();
    }

    /** The contract every variant renders into, or null for the pass's. */
    MrtPipelineContext.Contract contract;
    /** A bit per output location the fragment stage writes (SpirvOutputWrites). */
    int writtenOutputs = SpirvOutputWrites.ALL;
    /** The pack's blend per attachment of a VulkanMod render pass, or null to keep the host's. */
    PackBlendPlan.Mode[] hostPassBlends;
    /** The blend the pipeline's draws report in place of the host's, or null. */
    PackBlendPlan.Mode programBlend;
    /** The pack geometry stage's shader module, or 0 without one. */
    long geometryModule;

    /** {@code pipeline}'s side. */
    static PipelineSide of(GraphicsPipeline pipeline) {
        return ((Holder) (Object) pipeline).chimera$side();
    }

    /** The side of the pipeline a draw binds, or null when it is not a GraphicsPipeline. */
    static PipelineSide ofBound(Pipeline pipeline) {
        return pipeline instanceof Holder holder ? holder.chimera$side() : null;
    }
}
