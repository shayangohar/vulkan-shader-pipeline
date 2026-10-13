package net.chimera.render;

import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PackResourcePlan;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.ProgramImageBindingManifest;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Binds each pack program's images for a draw: the manifest every installed pipeline records, and
 * the transaction that resolves each entry to a target, depth copy, shadow map, material map or
 * pack texture, and restores the host bindings afterwards.
 */
final class PackImageBindings {
    private final ChimeraMainPass pass;

    final Map<GraphicsPipeline, ChimeraMainPass.ProgramImages> programImages = new IdentityHashMap<>();

    static final ProgramImageBindingManifest IDENTITY_IMAGES = new ProgramImageBindingManifest(List.of(
            new ProgramImageBindingManifest.Entry("Sampler0", 0, 1, 63,
                    ProgramImageBindingManifest.Kind.COLOR_TARGET, "colortex0", 0, 0, "colortex0")));

    static final ProgramImageBindingTransaction.Store<ChimeraTextureBindingState.Snapshot> IMAGE_STORE =
            new ProgramImageBindingTransaction.Store<>() {
                public ChimeraTextureBindingState.Snapshot capture(int slot) {
                    return ChimeraTextureBindingState.capture(slot, VTextureSelector.getImage(slot));
                }
                public boolean available(ChimeraTextureBindingState.Snapshot value) {
                    return value != null && value.image() != null;
                }
                public void bind(int slot, ChimeraTextureBindingState.Snapshot value) {
                    VTextureSelector.bindTexture(slot, value.image());
                    ChimeraTextureBindingState.restore(slot, value);
                }
                public void restore(int slot, ChimeraTextureBindingState.Snapshot value) {
                    bind(slot, value);
                }
            };

    PackImageBindings(ChimeraMainPass pass) {
        this.pass = pass;
    }

    public ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindDescriptorImages(
            GraphicsPipeline pipeline) {
        ChimeraMainPass.ProgramImages program = this.programImages.get(pipeline);
        // Fullscreen and terrain seams already hold a transaction with their exact graph inputs.
        if (program == null || PostTargetPlan.isPostProgramName(program.name())
                || (!pass.shadowPass.shadowPassActive && pass.packGeometryBindings != null)) return null;
        return bindProgramImages(pipeline);
    }

    /** All graphics families reach this seam before descriptor upload. */
    public ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindProgramImages(
            GraphicsPipeline pipeline, VulkanImage[] colors, VulkanImage fallback,
            DrawMaterialContext drawContext) {
        ChimeraMainPass.ProgramImages program = this.programImages.get(pipeline);
        if (program == null) {
            if (pipeline != pass.compositePipeline) return null;
            program = new ChimeraMainPass.ProgramImages("identity", IDENTITY_IMAGES, Set.of());
        }
        ChimeraMainPass.ProgramImages bound = program;
        return ProgramImageBindingTransaction.bind(bound.name(), bound.manifest(), IMAGE_STORE, entry -> {
            if (entry.kind() == ProgramImageBindingManifest.Kind.HOST_TEXTURE) {
                int hostSlot = PackResourcePlan.HOST_TEXTURE_SLOTS.get(entry.resourceKey());
                return IMAGE_STORE.capture(hostSlot);
            }
            if (entry.kind() == ProgramImageBindingManifest.Kind.MATERIAL_MAP) {
                // The context was captured before the first slot capture, so
                // the resolver sees the unmutated draw. Rollback stays with
                // the transaction.
                if (pass.materials.materialMapOwner == null) return null;
                return pass.materials.materialMapOwner.resolveDrawMaterial(drawContext, entry.resourceKey());
            }
            VulkanImage image = entryImage(entry, colors, fallback);
            return new ChimeraTextureBindingState.Snapshot(image,
                    image == null ? null : pass.shadowPass.shadowSamplerFor(bound, entry, image));
        });
    }

    /** The image one target, depth, shadow, pack or advanced manifest entry binds this draw. */
    private VulkanImage entryImage(ProgramImageBindingManifest.Entry entry, VulkanImage[] colors,
            VulkanImage fallback) {
        return switch (entry.kind()) {
            case COLOR_TARGET -> PackPostChain.resolvePackColorInput(
                    PackResourcePlan.targetIndex(entry.resourceKey()), colors, fallback);
            case DEPTH_TARGET -> depthTargetImage(entry.resourceKey());
            case SHADOW_DEPTH -> shadowDepthImage(entry.resourceKey());
            case SHADOW_COLOR ->
                    pass.shadowPass.shadowMap.shadowColor(entry.resourceKey().equals("shadowcolor1") ? 1 : 0);
            case PACK_TEXTURE -> pass.packResourceOwner == null ? null
                    : pass.packResourceOwner.image(entry.resourceKey());
            case ADVANCED_IMAGE -> pass.packAdvancedImageOwner == null ? null
                    : pass.packAdvancedImageOwner.image(entry.resourceKey());
            case MATERIAL_MAP -> throw new AssertionError();
            case HOST_TEXTURE -> throw new AssertionError();
        };
    }

    /** A depth snapshot; depthtex0 falls back to the live world depth until it is captured. */
    private VulkanImage depthTargetImage(String name) {
        VulkanImage depth = pass.packDepthTargets.image(name);
        return depth == null && name.equals("depthtex0")
                && pass.targets.hdrFramebuffer != null
                ? pass.targets.hdrFramebuffer.getDepthAttachment() : depth;
    }

    /** shadowtex1 is the opaque-only shadow depth; shadowtex0 is the full shadow depth. */
    private VulkanImage shadowDepthImage(String name) {
        ChimeraShadowMap shadowMap = pass.shadowPass.shadowMap;
        if (shadowMap.getShadowFramebuffer() == null) {
            return null;
        }
        return name.equals("shadowtex1") ? shadowMap.opaqueDepth()
                : shadowMap.getShadowFramebuffer().getDepthAttachment();
    }

    public ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindProgramImages(
            GraphicsPipeline pipeline) {
        return bindProgramImages(pipeline, pass.packPostTargets.sourceImages(),
                pass.targets.hdrFramebuffer == null ? null : pass.targets.hdrFramebuffer.getColorAttachment(),
                DrawMaterialContext.captureLive());
    }

    /**
     * True when every pack target and depth snapshot the water program samples
     * holds this frame's data. A failed early window or depth capture leaves
     * one invalid, and the manifest resolver would then reject the draw.
     */
    boolean packTranslucentInputsReady(PackPipelines.PackTerrain water) {
        ChimeraMainPass.ProgramImages program = this.programImages.get(water.pipeline());
        if (program == null) return false;
        for (ProgramImageBindingManifest.Entry entry : program.manifest().entries()) {
            boolean ready = switch (entry.kind()) {
                case COLOR_TARGET -> pass.packPostTargets.isConfigured()
                        && pass.packPostTargets.currentTargetAvailable(
                                PackResourcePlan.targetIndex(entry.resourceKey()));
                case DEPTH_TARGET -> pass.packDepthTargets.image(entry.resourceKey()) != null;
                default -> true;
            };
            if (!ready) return false;
        }
        return true;
    }

    /** Called before opening attachments, including host geometry render passes. */
    public void prepareProgramImages(GraphicsPipeline pipeline) {
        ChimeraMainPass.ProgramImages program = this.programImages.get(pipeline);
        if (program == null || pass.packAdvancedImageOwner == null
                || !pass.packAdvancedImageOwner.needsGraphicsTransition(program.manifest())) return;
        boolean resume = Renderer.getInstance().getBoundRenderPass() != null;
        if (resume) Renderer.getInstance().endRenderPass();
        try {
            pass.packAdvancedImageOwner.prepareGraphicsImages(Renderer.getCommandBuffer(), program.manifest());
        } finally {
            if (resume) pass.rebindMainTarget();
        }
    }
}
