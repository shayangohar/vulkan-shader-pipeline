package net.chimera.render.shader;

import net.chimera.ChimeraMod;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.MultiBufferSource;
import net.chimera.shaderpack.PackEntityIdResolver;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.UniformRegistry;
import net.chimera.render.EntityTransformBinding;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.EnumSet;
import java.util.Set;

/** Render-thread bridge for the guarded core geometry family lanes. */
public final class ChimeraEntityBridge {
    public enum Family {
        ENTITY,
        ENTITY_TRANSLUCENT,
        GLOWING,
        BLOCK,
        DAMAGED_BLOCK,
        HAND,
        HAND_WATER,
        PARTICLE,
        PARTICLE_TRANSLUCENT,
        WEATHER,
        HOST_FALLBACK
    }

    private static PackEntityIdResolver resolver = PackEntityIdResolver.empty();
    private static GraphicsPipeline entityPipeline;
    private static GraphicsPipeline translucentEntityPipeline;
    private static GraphicsPipeline glowingEntityPipeline;
    private static GraphicsPipeline blockPipeline;
    private static GraphicsPipeline damagedBlockPipeline;
    private static GraphicsPipeline handPipeline;
    private static GraphicsPipeline handWaterPipeline;
    private static GraphicsPipeline particlePipeline;
    private static GraphicsPipeline translucentParticlePipeline;
    private static GraphicsPipeline weatherPipeline;
    private static GraphicsPipeline activePipeline;
    private static Family activeFamily;
    /** The family requested for the current guarded draw, even if host fallback is selected. */
    private static Family requestedFamily;
    private static boolean enabled;
    private static int submittingDepth;
    private static int entitySubmissionDepth;
    private static int blockSubmissionDepth;
    private static final int[] previousEntityIds = new int[8];
    private static final int[] previousSubmissionFamilies = new int[8];
    private static int modelIdDepth;
    private static final int[] previousModelEntityIds = new int[8];
    private static boolean drawActive;
    private static int currentEntityId;
    private static boolean worldSubmissionWindow;
    private static boolean textureSnapshot;
    private static boolean entityBufferUnavailable;
    private static boolean submissionTraceLogged;
    private static final EnumSet<Family> drawTraceFamilies = EnumSet.noneOf(Family.class);
    private static final EnumSet<Family> separatedTraceFamilies = EnumSet.noneOf(Family.class);
    private static final EnumSet<Family> hostFallbackTraceFamilies = EnumSet.noneOf(Family.class);
    private static final EnumSet<Family> modelTraceFamilies = EnumSet.noneOf(Family.class);
    private static final EnumSet<Family> pipelineTraceFamilies = EnumSet.noneOf(Family.class);
    private static final EnumSet<Family> meshTraceFamilies = EnumSet.noneOf(Family.class);
    private static final EnumSet<Family> hostTransformBindingTraceFamilies = EnumSet.noneOf(Family.class);
    private static final EnumSet<Family> hostTransformAbandonTraceFamilies = EnumSet.noneOf(Family.class);
    private static final EnumSet<Family> transformContractTraceFamilies = EnumSet.noneOf(Family.class);
    private static boolean separatedBatchTraceLogged;
    private static boolean unsupportedPipelineTraceLogged;
    private static boolean flushTraceLogged;
    private static ByteBufferBuilder entityBuffer;
    private static MultiBufferSource.BufferSource entityBufferSource;
    private static final VulkanImage[] previousTextures =
            new VulkanImage[VTextureSelector.SIZE];
    private static final Set<RenderPipeline> SUPPORTED_WORLD_PIPELINES = Set.of(
            RenderPipelines.ENTITY_SOLID,
            RenderPipelines.ENTITY_SOLID_Z_OFFSET_FORWARD,
            RenderPipelines.ENTITY_CUTOUT,
            RenderPipelines.ENTITY_CUTOUT_NO_CULL,
            RenderPipelines.ENTITY_CUTOUT_NO_CULL_Z_OFFSET,
            RenderPipelines.ENTITY_SMOOTH_CUTOUT,
            RenderPipelines.ENTITY_NO_OUTLINE,
            RenderPipelines.ENTITY_TRANSLUCENT,
            RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE
    );
    private ChimeraEntityBridge() {}

    public static void install(PackPipelines.PackEntity entity,
                               PackEntityIdResolver nextResolver) {
        install(entity, null, null, null, null, null, null, null, null, null, nextResolver);
    }

    public static void install(
            PackPipelines.PackEntity entity,
            PackPipelines.PackEntity block,
            PackPipelines.PackEntity hand,
            PackPipelines.PackParticle particle,
            PackEntityIdResolver nextResolver
    ) {
        install(entity, null, null, block, null, hand, null, particle, null, null, nextResolver);
    }

    public static void install(
            PackPipelines.PackEntity entity,
            PackPipelines.PackEntity translucentEntity,
            PackPipelines.PackEntity glowingEntity,
            PackPipelines.PackEntity block,
            PackPipelines.PackEntity damagedBlock,
            PackPipelines.PackEntity hand,
            PackPipelines.PackEntity handWater,
            PackPipelines.PackParticle particle,
            PackPipelines.PackParticle translucentParticle,
            PackPipelines.PackParticle weather,
            PackEntityIdResolver nextResolver
    ) {
        releaseEntityBuffer();
        entityPipeline = entity == null ? null : entity.pipeline();
        translucentEntityPipeline = translucentEntity == null ? null : translucentEntity.pipeline();
        glowingEntityPipeline = glowingEntity == null ? null : glowingEntity.pipeline();
        blockPipeline = block == null ? null : block.pipeline();
        damagedBlockPipeline = damagedBlock == null ? null : damagedBlock.pipeline();
        handPipeline = hand == null ? null : hand.pipeline();
        handWaterPipeline = handWater == null ? null : handWater.pipeline();
        particlePipeline = particle == null ? null : particle.pipeline();
        translucentParticlePipeline = translucentParticle == null ? null : translucentParticle.pipeline();
        weatherPipeline = weather == null ? null : weather.pipeline();
        activePipeline = null;
        activeFamily = null;
        requestedFamily = null;
        resolver = nextResolver == null ? PackEntityIdResolver.empty() : nextResolver;
        currentEntityId = 0;
        drawActive = false;
        submittingDepth = 0;
        entitySubmissionDepth = 0;
        blockSubmissionDepth = 0;
        submissionFamily = ChimeraEntitySubmission.FAMILY_NONE;
        modelIdDepth = 0;
        worldSubmissionWindow = false;
        textureSnapshot = false;
        entityBufferUnavailable = false;
        submissionTraceLogged = false;
        drawTraceFamilies.clear();
        separatedTraceFamilies.clear();
        hostFallbackTraceFamilies.clear();
        modelTraceFamilies.clear();
        pipelineTraceFamilies.clear();
        meshTraceFamilies.clear();
        hostTransformBindingTraceFamilies.clear();
        hostTransformAbandonTraceFamilies.clear();
        transformContractTraceFamilies.clear();
        separatedBatchTraceLogged = false;
        unsupportedPipelineTraceLogged = false;
        flushTraceLogged = false;
        clearScopedState();
        ChimeraMod.LOGGER.info("[chimera] family bridge: entity={}, entityTranslucent={}, glowing={}, "
                        + "block={}, damagedBlock={}, hand={}, handWater={}, particle={}, particleTranslucent={}",
                status(entityPipeline), status(translucentEntityPipeline), status(glowingEntityPipeline),
                status(blockPipeline), status(damagedBlockPipeline), status(handPipeline),
                status(handWaterPipeline), status(particlePipeline), status(translucentParticlePipeline));
        if (weatherPipeline != null) {
            ChimeraMod.LOGGER.info("[chimera] family bridge: weather=installed");
        }
    }

    public static void setEnabled(boolean value) {
        enabled = value && isInstalled();
        if (!enabled) {
            restoreTextures();
            drawActive = false;
            activePipeline = null;
            activeFamily = null;
            requestedFamily = null;
            currentEntityId = 0;
            submittingDepth = 0;
            entitySubmissionDepth = 0;
            blockSubmissionDepth = 0;
            clearScopedState();
            submissionFamily = ChimeraEntitySubmission.FAMILY_NONE;
            worldSubmissionWindow = false;
        }
    }

    public static void disable() {
        releaseEntityBuffer();
        restoreTextures();
        enabled = false;
        drawActive = false;
        activePipeline = null;
        activeFamily = null;
        requestedFamily = null;
        submittingDepth = 0;
        entitySubmissionDepth = 0;
        blockSubmissionDepth = 0;
        clearScopedState();
        currentEntityId = 0;
        worldSubmissionWindow = false;
        submissionFamily = ChimeraEntitySubmission.FAMILY_NONE;
        entityPipeline = null;
        translucentEntityPipeline = null;
        glowingEntityPipeline = null;
        blockPipeline = null;
        damagedBlockPipeline = null;
        handPipeline = null;
        handWaterPipeline = null;
        particlePipeline = null;
        translucentParticlePipeline = null;
        weatherPipeline = null;
        resolver = PackEntityIdResolver.empty();
    }

    public static boolean isInstalled() {
        return entityPipeline != null || translucentEntityPipeline != null
                || glowingEntityPipeline != null || blockPipeline != null
                || damagedBlockPipeline != null || handPipeline != null
                || handWaterPipeline != null || particlePipeline != null
                || translucentParticlePipeline != null || weatherPipeline != null;
    }

    public static boolean isDrawActive() {
        return enabled && drawActive && activePipeline != null;
    }

    public static boolean isDrawActive(Family family) {
        return isDrawActive() && activeFamily == family;
    }

    public static GraphicsPipeline pipeline() {
        return activePipeline;
    }

    public static void beginEntity(String name) {
        if (!enabled || entityPipeline == null) {
            return;
        }
        if (submittingDepth < previousEntityIds.length) {
            previousEntityIds[submittingDepth] = currentEntityId;
            previousSubmissionFamilies[submittingDepth] = submissionFamily;
        }
        submittingDepth++;
        entitySubmissionDepth++;
        currentEntityId = resolver.resolveName(name);
        submissionFamily = ChimeraEntitySubmission.FAMILY_ENTITY;
        if (!submissionTraceLogged) {
            submissionTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] entity bridge: captured world entity type={} id={}",
                    name == null ? "unknown" : name, currentEntityId);
        }
    }

    public static void endEntity() {
        if (enabled && entitySubmissionDepth > 0) {
            entitySubmissionDepth--;
            popSubmission();
        }
    }

    /** Marks a delayed block-entity model submission for the block adapter. */
    public static void beginBlockEntity() {
        if (!enabled || blockPipeline == null) {
            return;
        }
        if (submittingDepth < previousEntityIds.length) {
            previousEntityIds[submittingDepth] = currentEntityId;
            previousSubmissionFamilies[submittingDepth] = submissionFamily;
        }
        submittingDepth++;
        blockSubmissionDepth++;
        currentEntityId = 0;
        submissionFamily = ChimeraEntitySubmission.FAMILY_BLOCK;
    }

    public static void endBlockEntity() {
        if (enabled && blockSubmissionDepth > 0) {
            blockSubmissionDepth--;
            popSubmission();
        }
    }

    public static boolean isSubmittingEntity() {
        return enabled && submittingDepth > 0;
    }

    public static int currentSubmissionFamily() {
        return enabled ? submissionFamily : ChimeraEntitySubmission.FAMILY_NONE;
    }

    public static boolean beginDraw() {
        return beginDraw(Family.ENTITY);
    }

    public static boolean beginDraw(Family family) {
        if (!enabled || drawActive) {
            return false;
        }
        GraphicsPipeline selected = pipelineFor(family);
        if (selected == null || !ensureEntityBuffer()) {
            return false;
        }
        // A family whose upload widens the vertex format can never hand its
        // mesh back to the host pipeline, so the pack pipeline must be able to
        // serve the host transform blocks before the batch is admitted. When it
        // cannot, this batch keeps the host format and the host draw from the
        // outset.
        if (!EntityTransformBinding.canServeHostTransforms(widensVertexFormat(family),
                selected.getUBO(UniformRegistry.DYNAMIC_TRANSFORMS_UBO) != null,
                selected.getUBO(UniformRegistry.PROJECTION_UBO) != null)) {
            noteTransformContractMissing(family, selected);
            return false;
        }
        requestedFamily = family;
        activeFamily = family;
        activePipeline = selected;
        for (int index = 0; index < VTextureSelector.SIZE; index++) {
            previousTextures[index] = VTextureSelector.getBoundTexture(index);
        }
        textureSnapshot = true;
        drawActive = true;
        if (drawTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: rendering family={} batch",
                    family.name().toLowerCase());
        }
        return true;
    }

    public static void endDraw() {
        restoreTextures();
        drawActive = false;
        activePipeline = null;
        activeFamily = null;
        requestedFamily = null;
        modelIdDepth = 0;
        currentEntityId = 0;
    }

    /** Opens a guarded hand draw without replacing the host hand buffer source. */
    public static boolean beginHandDraw() {
        Family family = handWaterPipeline != null && isCameraInWater()
                ? Family.HAND_WATER : Family.HAND;
        return beginSimpleDraw(family);
    }

    /** Opens a guarded particle draw on the host particle format. */
    public static boolean beginParticleDraw() {
        return beginSimpleDraw(particlePipeline != null
                ? Family.PARTICLE : Family.PARTICLE_TRANSLUCENT);
    }

    /** Opens a guarded weather draw on the host particle vertex contract. */
    public static boolean beginWeatherDraw() {
        return beginSimpleDraw(Family.WEATHER);
    }

    public static boolean isHandDrawActive() {
        return isDrawActive(Family.HAND) || isDrawActive(Family.HAND_WATER);
    }

    public static boolean isParticleDrawActive() {
        return enabled && drawActive
                && (requestedFamily == Family.PARTICLE
                || requestedFamily == Family.PARTICLE_TRANSLUCENT);
    }

    public static boolean isWeatherDrawActive() {
        return isDrawActive(Family.WEATHER);
    }

    private static boolean beginSimpleDraw(Family family) {
        if (!enabled || drawActive) {
            return false;
        }
        GraphicsPipeline selected = pipelineFor(family);
        if (selected == null) {
            return false;
        }
        requestedFamily = family;
        activeFamily = family;
        activePipeline = selected;
        for (int index = 0; index < VTextureSelector.SIZE; index++) {
            previousTextures[index] = VTextureSelector.getBoundTexture(index);
        }
        textureSnapshot = true;
        drawActive = true;
        if (drawTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: rendering family={} draw",
                    family.name().toLowerCase());
        }
        return true;
    }

    /** Saves the submission identity while one delayed model emits vertices. */
    public static void beginModelEntity(int entityId) {
        if (!enabled) {
            return;
        }
        if (modelIdDepth < previousModelEntityIds.length) {
            previousModelEntityIds[modelIdDepth] = currentEntityId;
        }
        modelIdDepth++;
        currentEntityId = entityId;
    }

    /** Restores the enclosing submission identity after model emission. */
    public static void endModelEntity() {
        if (!enabled || modelIdDepth <= 0) {
            return;
        }
        modelIdDepth--;
        currentEntityId = modelIdDepth < previousModelEntityIds.length
                ? previousModelEntityIds[modelIdDepth] : 0;
    }

    /**
     * Returns the session-stable buffer source reserved for world entity batches.
     *
     * The source is deliberately separate from the host immediate source. It
     * lets the guarded format mixins change only the upload path for this
     * batch, so host screen, inventory, and special-entity buffers keep their
     * normal 36-byte format and cache lifetime.
     */
    public static MultiBufferSource.BufferSource entityBufferSource() {
        return ensureEntityBuffer() ? entityBufferSource : null;
    }

    /** Flushes one dedicated source batch while the extended-format draw window is active. */
    public static void endEntityBatch(RenderType renderType) {
        if (entityBufferSource == null || !drawActive) {
            return;
        }
        if (!flushTraceLogged) {
            flushTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] entity bridge: flushing separated world batch");
        }
        if (renderType == null) {
            entityBufferSource.endBatch();
        } else {
            // The dedicated source has no fixed-buffer table. Flush the exact
            // render type so a started builder cannot remain queued when the
            // guarded family window closes.
            entityBufferSource.endBatch(renderType);
        }
    }

    /** Compatibility overload for lifecycle cleanup and older callers. */
    public static void endEntityBatch() {
        endEntityBatch(null);
    }

    public static int currentEntityId() {
        return enabled ? currentEntityId : 0;
    }

    public static void setCurrentEntityId(int value) {
        if (enabled) {
            currentEntityId = value;
        }
    }

    /** Marks the LevelRenderer interval in which world entities are submitted. */
    public static void beginWorldSubmissionWindow() {
        // Reserve the session-stable source before ModelFeatureRenderer moves
        // submissions into the separate batch. If allocation fails, the host
        // batch remains untouched and can render the entities normally.
        worldSubmissionWindow = enabled
                && (entityPipeline != null || translucentEntityPipeline != null
                || glowingEntityPipeline != null || blockPipeline != null
                || damagedBlockPipeline != null)
                && ensureEntityBuffer();
    }

    /** Ends the world submission interval; delayed model data keeps its mark. */
    public static void endWorldSubmissionWindow() {
        worldSubmissionWindow = false;
        currentEntityId = 0;
        submittingDepth = 0;
        entitySubmissionDepth = 0;
        blockSubmissionDepth = 0;
        clearScopedState();
        submissionFamily = ChimeraEntitySubmission.FAMILY_NONE;
    }

    public static boolean isWorldSubmissionWindow() {
        return enabled && worldSubmissionWindow;
    }

    /** True only when the separate entity batch can be flushed safely. */
    public static boolean isEntityBufferReady() {
        return enabled && (entityPipeline != null || translucentEntityPipeline != null
                || glowingEntityPipeline != null || blockPipeline != null
                || damagedBlockPipeline != null)
                && entityBufferSource != null;
    }

    public static boolean supportsSubmissionFamily(int family) {
        return switch (family) {
            case ChimeraEntitySubmission.FAMILY_ENTITY -> entityPipeline != null
                    || translucentEntityPipeline != null || glowingEntityPipeline != null;
            case ChimeraEntitySubmission.FAMILY_BLOCK -> blockPipeline != null
                    || damagedBlockPipeline != null;
            default -> false;
        };
    }

    /** Keeps emissive, hand, and special entity lanes on the host path. */
    public static boolean supportsWorldRenderType(RenderType renderType) {
        if (renderType == null) {
            return false;
        }
        // The separated source is EXTENDED_ENTITY. A RenderType may use an
        // entity-looking pipeline while still carrying a different host
        // format (held items use PARTICLE, for example). Keep those draws on
        // the original buffer so a 56-byte mesh can never reach a shorter
        // host layout.
        return renderType.format() == DefaultVertexFormat.NEW_ENTITY;
    }

    /** Compatibility query retained for the M6.3 conformance surface. */
    public static boolean supportsWorldPipeline(RenderPipeline pipeline) {
        return pipeline != null && SUPPORTED_WORLD_PIPELINES.contains(pipeline);
    }

    /** Selects the most specific installed entity lane for one host render type. */
    public static Family familyForRenderType(Family base, RenderType renderType) {
        if (base == Family.ENTITY && renderType != null) {
            if (renderType.pipeline() == RenderPipelines.ENTITY_TRANSLUCENT) {
                return translucentEntityPipeline != null
                        ? Family.ENTITY_TRANSLUCENT : Family.HOST_FALLBACK;
            }
            if (renderType.pipeline() == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE) {
                return glowingEntityPipeline != null
                        ? Family.GLOWING : Family.HOST_FALLBACK;
            }
        }
        return base;
    }

    /**
     * Keeps the Vulkan substitution limited to a host pipeline whose vertex
     * contract matches the active family. The hand hook also surrounds arm,
     * map, and other first-person draws, so a pack hand pipeline must never
     * replace one of those unrelated host formats.
     */
    public static boolean shouldUsePackPipeline(RenderPipeline pipeline) {
        if (!isDrawActive() || pipeline == null) {
            return false;
        }
        selectParticleFamily(pipeline);
        VertexFormat format = pipeline.getVertexFormat();
        return switch (activeFamily) {
            case ENTITY, ENTITY_TRANSLUCENT, GLOWING, BLOCK, DAMAGED_BLOCK ->
                    format == DefaultVertexFormat.NEW_ENTITY
                    || format == net.chimera.render.vertex.ChimeraVertexFormats.EXTENDED_ENTITY;
            case HAND, HAND_WATER -> format == DefaultVertexFormat.PARTICLE
                    || format == net.chimera.render.vertex.ChimeraVertexFormats.EXTENDED_PARTICLE;
            case PARTICLE, PARTICLE_TRANSLUCENT, WEATHER -> format == DefaultVertexFormat.PARTICLE;
            case HOST_FALLBACK -> false;
        };
    }

    /**
     * True when the active family's upload widens the host vertex format.
     *
     * <p>Such a mesh can never be handed to the host pipeline, so a draw that
     * cannot run the pack pipeline inside its family window is abandoned
     * instead of falling back once the batch exists.</p>
     */
    public static boolean requiresExtendedVertexFormat() {
        return isDrawActive() && widensVertexFormat(activeFamily);
    }

    /** Families that append pack inputs to the host format instead of drawing it directly. */
    private static boolean widensVertexFormat(Family family) {
        return switch (family) {
            case ENTITY, ENTITY_TRANSLUCENT, GLOWING, BLOCK, DAMAGED_BLOCK, HAND, HAND_WATER -> true;
            case PARTICLE, PARTICLE_TRANSLUCENT, WEATHER, HOST_FALLBACK -> false;
        };
    }

    /** Returns the append-only format for the active family when its host format matches. */
    public static VertexFormat extendedFormat(VertexFormat format) {
        if (!isDrawActive() || format == null) {
            return format;
        }
        return switch (activeFamily) {
            case ENTITY, ENTITY_TRANSLUCENT, GLOWING, BLOCK, DAMAGED_BLOCK ->
                    format == DefaultVertexFormat.NEW_ENTITY
                    ? net.chimera.render.vertex.ChimeraVertexFormats.EXTENDED_ENTITY : format;
            case HAND, HAND_WATER -> format == DefaultVertexFormat.PARTICLE
                    ? net.chimera.render.vertex.ChimeraVertexFormats.EXTENDED_PARTICLE : format;
            case PARTICLE, PARTICLE_TRANSLUCENT, WEATHER -> format;
            case HOST_FALLBACK -> format;
        };
    }

    /** True when the active family needs an append-only format. */
    public static boolean shouldExtendEntityFormat(VertexFormat format) {
        return extendedFormat(format) != format;
    }

    /** Records which separated family is about to emit a model batch. */
    public static void noteFamilyBatch(Family family, int renderTypeCount) {
        if (family == null || !separatedTraceFamilies.add(family)) {
            return;
        }
        ChimeraMod.LOGGER.info("[chimera] entity bridge: family={} separated render types={}",
                family.name().toLowerCase(), renderTypeCount);
    }

    /** Emits one diagnostic for the first separated pack entity batch. */
    public static void noteSeparatedBatch(RenderType renderType, int count) {
        if (!separatedBatchTraceLogged) {
            separatedBatchTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] entity bridge: separated world batch type={} count={}",
                    renderType == null ? "unknown" : renderType, count);
        }
    }

    /** Emits one diagnostic when a world entity uses a lane outside the contract. */
    public static void noteUnsupportedWorldPipeline(RenderType renderType) {
        if (!unsupportedPipelineTraceLogged) {
            unsupportedPipelineTraceLogged = true;
            ChimeraMod.LOGGER.info("[chimera] entity bridge: world entity lane kept on host type={} pipeline={}",
                    renderType == null ? "unknown" : renderType,
                    renderType == null ? "unknown" : renderType.pipeline());
        }
    }

    /**
     * Records a family batch that stayed on the host path because admission
     * failed before the mesh was built, once per family. The mesh therefore
     * keeps the host vertex format and the host pipeline draws it.
     */
    public static void noteFamilyHostFallback(Family family, RenderType renderType) {
        if (hostFallbackTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: family={} kept on the host path type={}",
                    family.name().toLowerCase(), renderType == null ? "unknown" : renderType);
        }
    }

    /** Emits one diagnostic when a delayed world entity reaches model emission. */
    public static void noteModelDraw(RenderType renderType) {
        Family family = activeFamily == null ? Family.HOST_FALLBACK : activeFamily;
        if (modelTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: model emission reached type={}",
                    renderType == null ? "unknown" : renderType);
        }
    }

    /** Emits one diagnostic after the Vulkan seam binds the pack entity pipeline. */
    public static void notePipelineBound(RenderPipeline hostPipeline) {
        Family family = activeFamily == null ? Family.HOST_FALLBACK : activeFamily;
        if (pipelineTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: pack pipeline bound family={} hostPipeline={}",
                    activeFamily == null ? "unknown" : activeFamily.name().toLowerCase(),
                    hostPipeline == null ? "unknown" : hostPipeline);
        }
    }

    /** Records the per-draw host transform routes for one family. */
    public static void noteHostTransformRoutes(String detail) {
        Family family = activeFamily == null ? Family.HOST_FALLBACK : activeFamily;
        if (hostTransformBindingTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: host raster UBOs family={} {}",
                    family.name().toLowerCase(), detail);
        }
    }

    /**
     * Records an abandoned guarded draw for one family.
     *
     * <p>A widened batch keeps its append-only mesh, so an unusable or missing
     * depth contract drops the draw. Routing it back to the host pipeline would
     * make that pipeline read the widened layout.</p>
     */
    public static void noteDrawAbandoned(String detail) {
        Family family = activeFamily == null ? Family.HOST_FALLBACK : activeFamily;
        if (hostTransformAbandonTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.warn("[chimera] entity bridge: abandoned guarded draw family={} {}",
                    family.name().toLowerCase(), detail);
        }
    }

    /** Records a family whose pack pipeline cannot serve the host transform blocks. */
    public static void noteTransformContractMissing(Family family, GraphicsPipeline pipeline) {
        if (transformContractTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.warn("[chimera] entity bridge: pack pipeline cannot serve host "
                            + "transforms family={} pipeline={} DynamicTransforms={} Projection={}; "
                            + "batch kept on the host format",
                    family.name().toLowerCase(), pipeline,
                    pipeline.getUBO(UniformRegistry.DYNAMIC_TRANSFORMS_UBO) != null,
                    pipeline.getUBO(UniformRegistry.PROJECTION_UBO) != null);
        }
    }

    /** Emits one diagnostic for the mesh handed to the guarded Vulkan draw. */
    public static void noteMeshDraw(int vertexCount, int indexCount, int stride) {
        Family family = activeFamily == null ? Family.HOST_FALLBACK : activeFamily;
        if (meshTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: mesh ready family={} vertices={} indices={} stride={}B",
                    activeFamily == null ? "unknown" : activeFamily.name().toLowerCase(),
                    vertexCount, indexCount, stride);
        }
    }

    private static void restoreTextures() {
        if (!textureSnapshot) {
            return;
        }
        for (int index = 0; index < VTextureSelector.SIZE; index++) {
            net.chimera.render.ChimeraTextureBindingState.clearPackBinding(index);
            VTextureSelector.bindTexture(index, previousTextures[index]);
            previousTextures[index] = null;
        }
        textureSnapshot = false;
    }

    private static boolean ensureEntityBuffer() {
        if (entityBufferSource != null) {
            return true;
        }
        if (entityBufferUnavailable || (entityPipeline == null && translucentEntityPipeline == null
                && glowingEntityPipeline == null && blockPipeline == null
                && damagedBlockPipeline == null)) {
            return false;
        }
        try {
            entityBuffer = new ByteBufferBuilder(256 * 1024);
            entityBufferSource = MultiBufferSource.immediate(entityBuffer);
            return true;
        } catch (RuntimeException exception) {
            releaseEntityBuffer();
            entityBufferUnavailable = true;
            ChimeraMod.LOGGER.warn("[chimera] entity buffer source unavailable; using host entity path", exception);
            return false;
        }
    }

    private static void releaseEntityBuffer() {
        if (entityBufferSource != null) {
            if (drawActive) {
                try {
                    entityBufferSource.endBatch();
                } catch (RuntimeException exception) {
                    ChimeraMod.LOGGER.warn("[chimera] entity buffer source cleanup failed", exception);
                }
            }
            entityBufferSource = null;
        }
        if (entityBuffer != null) {
            entityBuffer.close();
            entityBuffer = null;
        }
    }

    private static void clearScopedState() {
        modelIdDepth = 0;
        for (int index = 0; index < previousEntityIds.length; index++) {
            previousEntityIds[index] = 0;
            previousSubmissionFamilies[index] = ChimeraEntitySubmission.FAMILY_NONE;
            previousModelEntityIds[index] = 0;
        }
    }

    private static void popSubmission() {
        if (submittingDepth <= 0) {
            submissionFamily = ChimeraEntitySubmission.FAMILY_NONE;
            currentEntityId = 0;
            return;
        }
        submittingDepth--;
        currentEntityId = submittingDepth == 0
                ? 0
                : submittingDepth < previousEntityIds.length
                ? previousEntityIds[submittingDepth] : 0;
        submissionFamily = submittingDepth == 0
                ? ChimeraEntitySubmission.FAMILY_NONE
                : submittingDepth < previousSubmissionFamilies.length
                ? previousSubmissionFamilies[submittingDepth]
                : ChimeraEntitySubmission.FAMILY_NONE;
    }

    private static int submissionFamily = ChimeraEntitySubmission.FAMILY_NONE;

    private static GraphicsPipeline pipelineFor(Family family) {
        return switch (family) {
            case ENTITY -> entityPipeline;
            case ENTITY_TRANSLUCENT -> translucentEntityPipeline;
            case GLOWING -> glowingEntityPipeline;
            case BLOCK -> blockPipeline;
            case DAMAGED_BLOCK -> damagedBlockPipeline;
            case HAND -> handPipeline;
            case HAND_WATER -> handWaterPipeline;
            case PARTICLE -> particlePipeline;
            case PARTICLE_TRANSLUCENT -> translucentParticlePipeline;
            case WEATHER -> weatherPipeline;
            case HOST_FALLBACK -> null;
        };
    }

    private static void selectParticleFamily(RenderPipeline hostPipeline) {
        if (requestedFamily != Family.PARTICLE && requestedFamily != Family.PARTICLE_TRANSLUCENT) {
            return;
        }
        if (hostPipeline == RenderPipelines.TRANSLUCENT_PARTICLE
                && translucentParticlePipeline != null) {
            activeFamily = Family.PARTICLE_TRANSLUCENT;
            activePipeline = translucentParticlePipeline;
        } else if (hostPipeline == RenderPipelines.OPAQUE_PARTICLE
                && particlePipeline != null) {
            activeFamily = Family.PARTICLE;
            activePipeline = particlePipeline;
        } else {
            // Do not reuse an opaque pack shader for translucent particles,
            // or a translucent shader for opaque particles. The host state
            // and ordering are part of each family contract.
            activeFamily = Family.HOST_FALLBACK;
            activePipeline = null;
        }
    }

    private static boolean isCameraInWater() {
        try {
            return net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera()
                    .getFluidInCamera() == net.minecraft.world.level.material.FogType.WATER;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String status(GraphicsPipeline value) {
        return value == null ? "fallback" : "installed";
    }
}
