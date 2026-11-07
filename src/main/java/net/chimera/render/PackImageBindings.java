package net.chimera.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PackResourcePlan;
import net.chimera.shaderpack.PostTargetPlan;
import net.chimera.shaderpack.ProgramImageBindingManifest;
import net.vulkanmod.gl.GlSampler;
import net.vulkanmod.gl.GlTexture;
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
        if (program == null || PostTargetPlan.isFullscreenProgramName(program.name())
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
        return bind(program, colors, fallback, drawContext);
    }

    /**
     * Binds the images a pass owns across all its draws, before the first: its targets, depths,
     * shadow maps and pack images. Host textures and material maps follow each draw's albedo, so
     * each draw binds them after the host's selector (bindDescriptorImages). Before the first draw a
     * host slot holds whatever an earlier draw left, or nothing once a pack cleanup cleared it.
     */
    public ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindPassImages(
            GraphicsPipeline pipeline) {
        ChimeraMainPass.ProgramImages program = this.programImages.get(pipeline);
        if (program == null) return null;
        ProgramImageBindingManifest owned = new ProgramImageBindingManifest(program.manifest().entries().stream()
                .filter(entry -> !followsDraw(entry.kind())).toList());
        // No material map entry remains, so no draw context is read.
        return bind(new ChimeraMainPass.ProgramImages(program.name(), owned, program.shadowSamplerSymbols()),
                null, null, null);
    }

    /**
     * The overlay entityColor reads: the texture on the game's overlay unit. A render type without
     * an overlay (eyes, energy swirl) leaves the unit empty, where GL samples an opaque texel and
     * entityColor comes out zero; the white texture is that opaque texel.
     */
    private static ChimeraTextureBindingState.Snapshot overlay(int unit) {
        GlTexture texture = GlTexture.getTexture(RenderSystem.getShaderTexture(unit));
        VulkanImage image = texture == null ? null : texture.getVulkanImage();
        return new ChimeraTextureBindingState.Snapshot(image != null ? image : VTextureSelector.getWhiteTexture(),
                null);
    }

    private static boolean followsDraw(ProgramImageBindingManifest.Kind kind) {
        return kind == ProgramImageBindingManifest.Kind.HOST_TEXTURE
                || kind == ProgramImageBindingManifest.Kind.MATERIAL_MAP;
    }

    private ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bind(
            ChimeraMainPass.ProgramImages bound, VulkanImage[] colors, VulkanImage fallback,
            DrawMaterialContext drawContext) {
        return ProgramImageBindingTransaction.bind(bound.name(), bound.manifest(), IMAGE_STORE,
                entry -> asGl(entry.slot(), resolve(bound, entry, colors, fallback, drawContext)));
    }

    private ChimeraTextureBindingState.Snapshot resolve(ChimeraMainPass.ProgramImages bound,
            ProgramImageBindingManifest.Entry entry, VulkanImage[] colors, VulkanImage fallback,
            DrawMaterialContext drawContext) {
        if (entry.kind() == ProgramImageBindingManifest.Kind.HOST_TEXTURE) {
            int hostSlot = PackResourcePlan.HOST_TEXTURE_SLOTS.get(entry.resourceKey());
            return PackResourcePlan.HOST_BOUND_SLOTS.contains(hostSlot)
                    ? IMAGE_STORE.capture(hostSlot) : overlay(hostSlot);
        }
        if (entry.kind() == ProgramImageBindingManifest.Kind.MATERIAL_MAP) {
            // The context was captured before the first slot capture, so
            // the resolver sees the unmutated draw. Rollback stays with
            // the transaction.
            if (pass.materials.materialMapOwner == null) return null;
            return pass.materials.materialMapOwner.resolveDrawMaterial(drawContext, entry.resourceKey());
        }
        VulkanImage image = entry.kind() == ProgramImageBindingManifest.Kind.TARGET_IMAGE
                && !PostTargetPlan.isFullscreenProgramName(bound.name())
                ? pass.packPostTargets.worldStorageImage(PackResourcePlan.targetIndex(entry.resourceKey()))
                : entryImage(entry, colors, fallback);
        return new ChimeraTextureBindingState.Snapshot(image,
                image == null ? null : pass.shadowPass.shadowSamplerFor(bound, entry, image));
    }

    /**
     * {@code bound} as the pack program at {@code slot} samples it: with the sampler its descriptor
     * would take, as OpenGL samples it (SamplerProfile.asGl): at bias 0, and clamped where vanilla
     * asked. Only a sampler VulkanMod built from texture flags changes, such as the block atlas's
     * mipmapped one or the entity shadow's.
     */
    private static ChimeraTextureBindingState.Snapshot asGl(int slot, ChimeraTextureBindingState.Snapshot bound) {
        if (bound == null || bound.image() == null) return bound;
        long sampler = bound.samplerOverride() != null ? bound.samplerOverride()
                : GlSampler.resolveSampler(slot, bound.image());
        long gl = SamplerProfile.ofId(sampler).asGl(bound.image()).id();
        return gl == sampler ? bound : new ChimeraTextureBindingState.Snapshot(bound.image(), gl);
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
                    pass.shadowPass.shadowMap.shadowColor(PackResourcePlan.shadowColorIndex(entry.resourceKey()));
            case PACK_TEXTURE -> pass.packResourceOwner == null ? null
                    : pass.packResourceOwner.image(entry.resourceKey());
            case ADVANCED_IMAGE -> pass.packAdvancedImageOwner == null ? null
                    : pass.packAdvancedImageOwner.image(entry.resourceKey());
            // A pass's: the image the chain reads now, which PostTargetStorage holds in the general
            // layout. A hand program's resolves in resolve(), to the image world programs read.
            case TARGET_IMAGE -> pass.packPostTargets.storageSource(Renderer.getCommandBuffer(),
                    PackResourcePlan.targetIndex(entry.resourceKey()));
            case CENTER_DEPTH -> pass.packCenterDepth == null ? null : pass.packCenterDepth.image();
            case MATERIAL_MAP -> throw new AssertionError();
            case HOST_TEXTURE -> throw new AssertionError();
        };
    }

    /**
     * A depth snapshot. depthtex1 and depthtex2 read their last capture until this frame's.
     * depthtex0 reads this frame's capture: while the world depth is still the attachment world
     * programs draw into, prepareProgramImages has captured the world drawn so far, since sampling
     * that attachment would be a feedback loop. Without one, it reads the live world depth once
     * that is readable, and the last capture before then.
     */
    private VulkanImage depthTargetImage(String name) {
        if (!name.equals("depthtex0")) return pass.packDepthTargets.sampledImage(name);
        VulkanImage depth = pass.packDepthTargets.image(name);
        if (depth != null) return depth;
        if (!pass.depth.hdrDepthReadable || pass.targets.hdrFramebuffer == null) {
            return pass.packDepthTargets.sampledImage(name);
        }
        return pass.targets.hdrFramebuffer.getDepthAttachment();
    }

    /**
     * shadowtex1 is the opaque-only shadow depth; shadowtex0 is the full shadow depth. A shadow program
     * reads shadowtex1 from its copy, never from the attachment the pass draws into.
     */
    VulkanImage shadowDepthImage(String name) {
        ChimeraShadowMap shadowMap = pass.shadowPass.shadowMap;
        if (shadowMap.getShadowFramebuffer() == null) {
            return null;
        }
        if (!name.equals("shadowtex1")) return shadowMap.getShadowFramebuffer().getDepthAttachment();
        return pass.shadowPass.shadowPassActive ? shadowMap.opaqueDepthCopy() : shadowMap.opaqueDepth();
    }

    /**
     * Binds a program's images at a draw: a fullscreen program reads the chain's sources, a world
     * program what the targets hold (PackPostTargets.worldImages).
     */
    public ProgramImageBindingTransaction<ChimeraTextureBindingState.Snapshot> bindProgramImages(
            GraphicsPipeline pipeline) {
        ChimeraMainPass.ProgramImages program = this.programImages.get(pipeline);
        boolean fullscreen = program != null && PostTargetPlan.isFullscreenProgramName(program.name());
        return bindProgramImages(pipeline,
                fullscreen ? pass.packPostTargets.sourceImages() : pass.packPostTargets.worldImages(),
                hdrColor(), DrawMaterialContext.captureLive());
    }

    private VulkanImage hdrColor() {
        return pass.targets.hdrFramebuffer == null ? null : pass.targets.hdrFramebuffer.getColorAttachment();
    }

    /**
     * True when every pack target and depth snapshot the water program samples
     * can be bound: a target as world programs read it (PackPostTargets.worldImages). A failed
     * depth capture leaves one missing, and the manifest resolver would then reject the draw.
     * depthtex0 counts while the world depth can still be captured: the
     * program's prepare captures it before the layer draws.
     */
    boolean packTranslucentInputsReady(PackPipelines.PackTerrain water) {
        ChimeraMainPass.ProgramImages program = this.programImages.get(water.pipeline());
        if (program == null) return false;
        for (ProgramImageBindingManifest.Entry entry : program.manifest().entries()) {
            boolean ready = switch (entry.kind()) {
                case COLOR_TARGET -> pass.packPostTargets.isConfigured()
                        && PackPostChain.resolvePackColorInput(PackResourcePlan.targetIndex(entry.resourceKey()),
                                pass.packPostTargets.worldImages(), hdrColor()) != null;
                case DEPTH_TARGET -> pass.packDepthTargets.image(entry.resourceKey()) != null
                        || entry.resourceKey().equals("depthtex0") && pass.depth.drawnDepthCapturable();
                default -> true;
            };
            if (!ready) return false;
        }
        return true;
    }

    /**
     * Called before opening attachments, including host geometry render passes: captures the
     * depthtex0 a program reads while the world still draws, moves its advanced images into
     * their graphics layouts, and holds the targets a hand program stores to as colorimgN in the
     * general layout until its lane run ends (releaseWorldStorage).
     */
    public void prepareProgramImages(GraphicsPipeline pipeline) {
        ChimeraMainPass.ProgramImages program = this.programImages.get(pipeline);
        if (program == null) return;
        boolean drawnDepth = pass.depth.needsDrawnDepth(pipeline);
        boolean advanced = pass.packAdvancedImageOwner != null
                && pass.packAdvancedImageOwner.needsGraphicsTransition(program.manifest());
        List<Integer> stored = worldStorageTargets(program);
        boolean storage = !stored.isEmpty() && !pass.packPostTargets.holdsWorldStorage(stored);
        if (!drawnDepth && !advanced && !storage) return;
        boolean resume = Renderer.getInstance().getBoundRenderPass() != null;
        if (resume) Renderer.getInstance().endRenderPass();
        try {
            if (drawnDepth) pass.depth.captureDrawnDepth(Renderer.getCommandBuffer());
            if (advanced) {
                pass.packAdvancedImageOwner.prepareGraphicsImages(Renderer.getCommandBuffer(), program.manifest());
            }
            if (storage) pass.packPostTargets.holdWorldStorage(Renderer.getCommandBuffer(), stored);
        } finally {
            if (resume) pass.rebindMainTarget();
        }
    }

    /** The targets a world program stores to as colorimgN (a hand program, TargetImages), ascending. */
    private static List<Integer> worldStorageTargets(ChimeraMainPass.ProgramImages program) {
        if (PostTargetPlan.isFullscreenProgramName(program.name())) return List.of();
        return program.manifest().entries().stream()
                .filter(entry -> entry.kind() == ProgramImageBindingManifest.Kind.TARGET_IMAGE)
                .map(entry -> PackResourcePlan.targetIndex(entry.resourceKey())).distinct().sorted().toList();
    }

    /**
     * Returns the targets a hand lane's run stored to as colorimgN to the sampled layout, once the
     * run has drawn: VulkanMod moves no image out of GENERAL ("Unexpected value:1"), and later draws
     * sample them. Called outside or between render passes; one bound here reopens.
     */
    public void releaseWorldStorage() {
        if (!pass.packPostTargets.holdsWorldStorage()) return;
        boolean resume = Renderer.getInstance().getBoundRenderPass() != null;
        if (resume) Renderer.getInstance().endRenderPass();
        try {
            pass.packPostTargets.releaseWorldStorage(Renderer.getCommandBuffer());
        } finally {
            if (resume) pass.rebindMainTarget();
        }
    }
}
