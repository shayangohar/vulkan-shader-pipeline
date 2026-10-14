package net.chimera.render.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.ChimeraMod;
import net.chimera.render.ChimeraTextureBindingState;
import net.chimera.render.EntityTransformBinding;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.shaderpack.PackEntityIdResolver;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.UniformRegistry;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Render-thread bridge for the guarded core geometry family lanes. */
public final class ChimeraEntityBridge {
    public enum Family {
        ENTITY,
        ENTITY_TRANSLUCENT,
        SPIDER_EYES,
        BLOCK,
        /** A block entity's text (signs), drawn with gbuffers_block_translucent (Iris TEXT_BE). */
        BLOCK_TRANSLUCENT,
        DAMAGED_BLOCK,
        HAND,
        HAND_WATER,
        PARTICLE,
        PARTICLE_TRANSLUCENT,
        WEATHER,
        LINE,
        GLINT,
        /** First-person glint: the glint program under the hand projection. */
        HAND_GLINT,
        LIGHTNING,
        BEACON_BEAM,
        LEASH,
        /** Text and name-tag backgrounds (Iris TEXT_BG), drawn with gbuffers_entities_translucent. */
        TEXT_BG,
        /** Every entity-format caster in the shadow pass (Iris SHADOW_ENTITIES_CUTOUT). */
        SHADOW_ENTITIES,
        /** The shadow pass's host-layout lanes (HostLayoutLane, Pass.SHADOW). */
        SHADOW_LINES,
        SHADOW_LEASH,
        SHADOW_LIGHTNING,
        SHADOW_GLINT,
        SHADOW_TEXT,
        SHADOW_TEXT_BG,
        SHADOW_BASIC,
        HOST_FALLBACK
    }

    private static PackEntityIdResolver resolver = PackEntityIdResolver.empty();
    private static GraphicsPipeline entityPipeline;
    private static GraphicsPipeline translucentEntityPipeline;
    private static GraphicsPipeline spiderEyesPipeline;
    private static GraphicsPipeline blockPipeline;
    private static GraphicsPipeline damagedBlockPipeline;
    private static GraphicsPipeline handPipeline;
    private static GraphicsPipeline handWaterPipeline;
    private static PackPipelines.PackHand handVariants;
    private static PackPipelines.PackHand handWaterVariants;
    /** The translucent-entity and block-translucent programs built for Iris's GLYPH text layout. */
    private static GraphicsPipeline translucentEntityTextPipeline;
    private static GraphicsPipeline blockTranslucentTextPipeline;
    private static GraphicsPipeline particlePipeline;
    private static GraphicsPipeline translucentParticlePipeline;
    /** The installed lanes of {@link HostLayoutLane}, keyed by their family. */
    private static final EnumMap<Family, GraphicsPipeline> hostLayoutPipelines =
            new EnumMap<>(Family.class);
    /** shadow_entities, drawing every entity-format caster in the shadow pass. */
    private static GraphicsPipeline shadowCasterPipeline;
    /**
     * True while the shadow pass submits and draws entity and block-entity casters (Iris
     * ShadowRenderer.ACTIVE): entity-format batches select shadow_entities, nothing else draws.
     */
    private static boolean shadowCasterPass;
    private static final Set<RenderPipeline> droppedShadowPipelines = new HashSet<>();
    private static GraphicsPipeline activePipeline;
    static Family activeFamily;
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
    private static final int[] previousBlockEntityIds = new int[8];
    private static final int[] previousModelBlockEntityIds = new int[8];
    private static boolean drawActive;
    private static int currentEntityId;
    /** Iris CapturedRenderingState current block entity: EntityIds.y, read as blockEntityId. */
    private static int currentBlockEntityId;
    private static boolean worldSubmissionWindow;
    private static boolean textureSnapshot;
    private static boolean entityBufferUnavailable;
    private static boolean submissionTraceLogged;
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
            RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE,
            // Flat held and dropped items. Drawn by the host they wrote no
            // gbuffer data, and deferred lighting erased them to a ghost.
            RenderPipelines.ITEM_ENTITY_TRANSLUCENT_CULL
    );
    private ChimeraEntityBridge() {}

    public static void install(PackPipelines.PackEntity entity,
                               PackEntityIdResolver nextResolver) {
        install(entity, null, null, null, null, null, null, null, null, nextResolver);
    }

    public static void install(
            PackPipelines.PackEntity entity,
            PackPipelines.PackEntity block,
            PackPipelines.PackHand hand,
            PackPipelines.PackParticle particle,
            PackEntityIdResolver nextResolver
    ) {
        install(entity, null, null, block, null, hand, null, particle, null, nextResolver);
    }

    /** Installs the family lanes; the host-layout lanes follow through {@link #installHostLayout}. */
    public static void install(
            PackPipelines.PackEntity entity,
            PackPipelines.PackEntity translucentEntity,
            PackPipelines.PackEntity spiderEyes,
            PackPipelines.PackEntity block,
            PackPipelines.PackEntity damagedBlock,
            PackPipelines.PackHand hand,
            PackPipelines.PackHand handWater,
            PackPipelines.PackParticle particle,
            PackPipelines.PackParticle translucentParticle,
            PackEntityIdResolver nextResolver
    ) {
        releaseEntityBuffer();
        entityPipeline = entity == null ? null : entity.pipeline();
        translucentEntityPipeline = translucentEntity == null ? null : translucentEntity.pipeline();
        spiderEyesPipeline = spiderEyes == null ? null : spiderEyes.pipeline();
        blockPipeline = block == null ? null : block.pipeline();
        damagedBlockPipeline = damagedBlock == null ? null : damagedBlock.pipeline();
        handVariants = hand;
        handWaterVariants = handWater;
        translucentEntityTextPipeline = null;
        blockTranslucentTextPipeline = null;
        handPipeline = hand == null ? null : hand.primary().pipeline();
        handWaterPipeline = handWater == null ? null : handWater.primary().pipeline();
        particlePipeline = particle == null ? null : particle.pipeline();
        translucentParticlePipeline = translucentParticle == null ? null : translucentParticle.pipeline();
        hostLayoutPipelines.clear();
        shadowCasterPipeline = null;
        shadowCasterPass = false;
        droppedShadowPipelines.clear();
        activePipeline = null;
        activeFamily = null;
        requestedFamily = null;
        resolver = nextResolver == null ? PackEntityIdResolver.empty() : nextResolver;
        currentEntityId = 0;
        drawActive = false;
        submittingDepth = 0;
        entitySubmissionDepth = 0;
        blockSubmissionDepth = 0;
        submissionFamily = SubmissionIdentity.FAMILY_NONE;
        modelIdDepth = 0;
        worldSubmissionWindow = false;
        textureSnapshot = false;
        entityBufferUnavailable = false;
        submissionTraceLogged = false;
        FamilyDrawTrace.reset();
        clearScopedState();
        ChimeraMod.LOGGER.info("[chimera] family bridge: entity={}, entityTranslucent={}, spiderEyes={}, "
                        + "block={}, damagedBlock={}, hand={}, handWater={}, particle={}, particleTranslucent={}",
                status(entityPipeline), status(translucentEntityPipeline), status(spiderEyesPipeline),
                status(blockPipeline), status(damagedBlockPipeline), status(handPipeline),
                status(handWaterPipeline), status(particlePipeline), status(translucentParticlePipeline));
    }

    /**
     * Installs the host-layout lanes after {@link #install}. A lane without a pipeline keeps
     * its host draw.
     */
    public static void installHostLayout(Map<Family, PackPipelines.PackParticle> lanes) {
        hostLayoutPipelines.clear();
        if (lanes != null) {
            lanes.forEach((family, lane) -> {
                if (HostLayoutLane.of(family) != null && lane != null) hostLayoutPipelines.put(family, lane.pipeline());
            });
        }
        if (!hostLayoutPipelines.isEmpty()) {
            ChimeraMod.LOGGER.info("[chimera] family bridge: host-layout lanes={}",
                    hostLayoutPipelines.keySet().stream().map(family -> family.name().toLowerCase()).toList());
        }
    }

    /** Installs shadow_entities after {@link #install}; without it no entity casts a pack shadow. */
    public static void installShadowCasters(PackPipelines.PackEntity casters) {
        shadowCasterPipeline = casters == null ? null : casters.pipeline();
        shadowCasterPass = false;
        droppedShadowPipelines.clear();
        ChimeraMod.LOGGER.info("[chimera] family bridge: shadow casters={}", status(shadowCasterPipeline));
    }

    /**
     * Opens the shadow pass's caster window around submission and drawing (Iris
     * ShadowRenderer.ACTIVE). Inside it every entity-format batch draws with shadow_entities;
     * the window never nests inside a family draw.
     */
    public static boolean beginShadowCasters() {
        shadowCasterPass = enabled && !drawActive && shadowCasterPipeline != null;
        return shadowCasterPass;
    }

    public static void endShadowCasters() {
        shadowCasterPass = false;
    }

    public static boolean isShadowCasterPass() {
        return enabled && shadowCasterPass;
    }

    /**
     * Records, once per pipeline, a shadow-pass draw Chimera has no shadow lane for. Iris draws
     * it with a shadow program (lines, leads, lightning, text, glint); here it is left out of
     * the shadow map rather than drawn there with its host shader.
     */
    public static void noteShadowCasterDropped(RenderPipeline pipeline) {
        if (pipeline != null && droppedShadowPipelines.add(pipeline)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: shadow pass left out pipeline={}",
                    pipeline.getLocation());
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
            submissionFamily = SubmissionIdentity.FAMILY_NONE;
            worldSubmissionWindow = false;
            shadowCasterPass = false;
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
        submissionFamily = SubmissionIdentity.FAMILY_NONE;
        entityPipeline = null;
        translucentEntityPipeline = null;
        spiderEyesPipeline = null;
        blockPipeline = null;
        damagedBlockPipeline = null;
        handPipeline = null;
        handWaterPipeline = null;
        handVariants = null;
        handWaterVariants = null;
        translucentEntityTextPipeline = null;
        blockTranslucentTextPipeline = null;
        particlePipeline = null;
        translucentParticlePipeline = null;
        hostLayoutPipelines.clear();
        shadowCasterPipeline = null;
        shadowCasterPass = false;
        droppedShadowPipelines.clear();
        resolver = PackEntityIdResolver.empty();
    }

    public static boolean isInstalled() {
        return entityPipeline != null || translucentEntityPipeline != null
                || spiderEyesPipeline != null || blockPipeline != null
                || damagedBlockPipeline != null || handPipeline != null
                || handWaterPipeline != null || particlePipeline != null
                || translucentParticlePipeline != null || !hostLayoutPipelines.isEmpty()
                || shadowCasterPipeline != null;
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

    public static Family activeFamily() {
        return activeFamily;
    }

    public static void beginEntity(String name) {
        if (!enabled || (entityPipeline == null && !isShadowCasterPass())) {
            return;
        }
        if (submittingDepth < previousEntityIds.length) {
            previousEntityIds[submittingDepth] = currentEntityId;
            previousBlockEntityIds[submittingDepth] = currentBlockEntityId;
            previousSubmissionFamilies[submittingDepth] = submissionFamily;
        }
        submittingDepth++;
        entitySubmissionDepth++;
        currentEntityId = resolver.resolveName(name);
        currentBlockEntityId = 0;
        submissionFamily = SubmissionIdentity.FAMILY_ENTITY;
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

    /**
     * Marks a delayed block-entity submission for the block adapter. Like Iris
     * MixinBlockEntityRenderDispatcher, the block state's block.properties id
     * becomes blockEntityId for everything the block entity submits.
     */
    public static void beginBlockEntity(int blockEntityId) {
        if (!enabled || (blockPipeline == null && !isShadowCasterPass())) {
            return;
        }
        if (submittingDepth < previousEntityIds.length) {
            previousEntityIds[submittingDepth] = currentEntityId;
            previousBlockEntityIds[submittingDepth] = currentBlockEntityId;
            previousSubmissionFamilies[submittingDepth] = submissionFamily;
        }
        submittingDepth++;
        blockSubmissionDepth++;
        currentEntityId = 0;
        currentBlockEntityId = blockEntityId;
        submissionFamily = SubmissionIdentity.FAMILY_BLOCK;
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
        return enabled ? submissionFamily : SubmissionIdentity.FAMILY_NONE;
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
            FamilyDrawTrace.noteTransformContractMissing(family, selected);
            return false;
        }
        activate(family, selected, "batch");
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

    /** Iris selects the hand family by the scheduled half, not camera immersion. */
    public static boolean beginHandDraw(boolean translucent) {
        return beginSimpleDraw(translucent ? Family.HAND_WATER : Family.HAND);
    }

    /** Opens a guarded particle draw on the host particle format. */
    public static boolean beginParticleDraw() {
        return beginSimpleDraw(particlePipeline != null
                ? Family.PARTICLE : Family.PARTICLE_TRANSLUCENT);
    }

    /**
     * Opens a guarded draw for a {@link HostLayoutLane}, which keeps its host vertex layout.
     * Returns false for any other family, and for a lane of the other pass: shadow lanes draw
     * only inside the shadow caster window, the others never inside it.
     */
    public static boolean beginHostLayoutDraw(Family family) {
        HostLayoutLane lane = HostLayoutLane.of(family);
        return lane != null && (lane.pass() == HostLayoutLane.Pass.SHADOW) == isShadowCasterPass()
                && beginSimpleDraw(family);
    }

    public static boolean isParticleDrawActive() {
        return enabled && drawActive
                && (requestedFamily == Family.PARTICLE
                || requestedFamily == Family.PARTICLE_TRANSLUCENT);
    }

    public static boolean beginCrumblingDraw() {
        return beginSimpleDraw(Family.DAMAGED_BLOCK);
    }

    private static boolean beginSimpleDraw(Family family) {
        if (!enabled || drawActive) {
            return false;
        }
        GraphicsPipeline selected = pipelineFor(family);
        if (selected == null) {
            return false;
        }
        if (family == Family.DAMAGED_BLOCK && !EntityTransformBinding.canServeHostTransforms(true,
                selected.getUBO(UniformRegistry.DYNAMIC_TRANSFORMS_UBO) != null,
                selected.getUBO(UniformRegistry.PROJECTION_UBO) != null)) {
            FamilyDrawTrace.noteTransformContractMissing(family, selected);
            return false;
        }
        activate(family, selected, "draw");
        return true;
    }

    /** Makes {@code selected} the active pipeline for {@code family}, saving the bound textures to restore. */
    private static void activate(Family family, GraphicsPipeline selected, String kind) {
        requestedFamily = family;
        activeFamily = family;
        activePipeline = selected;
        for (int index = 0; index < VTextureSelector.SIZE; index++) {
            previousTextures[index] = VTextureSelector.getBoundTexture(index);
        }
        textureSnapshot = true;
        drawActive = true;
        if (FamilyDrawTrace.drawTraceFamilies.add(family)) {
            ChimeraMod.LOGGER.info("[chimera] entity bridge: rendering family={} {}", family.name().toLowerCase(),
                    kind);
        }
    }

    /** Saves the submission identity while one delayed model emits vertices. */
    public static void beginModelEntity(int entityId, int blockEntityId) {
        if (!enabled) {
            return;
        }
        if (modelIdDepth < previousModelEntityIds.length) {
            previousModelEntityIds[modelIdDepth] = currentEntityId;
            previousModelBlockEntityIds[modelIdDepth] = currentBlockEntityId;
        }
        modelIdDepth++;
        currentEntityId = entityId;
        currentBlockEntityId = blockEntityId;
    }

    /** Restores the enclosing submission identity after model emission. */
    public static void endModelEntity() {
        if (!enabled || modelIdDepth <= 0) {
            return;
        }
        modelIdDepth--;
        currentEntityId = modelIdDepth < previousModelEntityIds.length
                ? previousModelEntityIds[modelIdDepth] : 0;
        currentBlockEntityId = modelIdDepth < previousModelBlockEntityIds.length
                ? previousModelBlockEntityIds[modelIdDepth] : 0;
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
        if (!FamilyDrawTrace.flushTraceLogged) {
            FamilyDrawTrace.flushTraceLogged = true;
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

    public static int currentEntityId() {
        return enabled ? currentEntityId : 0;
    }

    /**
     * The entity id Iris gives burning-entity fire (MixinFlameFeatureRenderer): the pack's
     * entity.properties id for minecraft:entity_flame.
     */
    public static int flameEntityId() {
        return resolver.resolveName("minecraft:entity_flame");
    }

    public static int currentBlockEntityId() {
        return enabled ? currentBlockEntityId : 0;
    }

    /** Iris setCurrentBlockEntity: replaces the current block entity id and returns the one replaced. */
    public static int replaceBlockEntityId(int blockEntityId) {
        int previous = currentBlockEntityId;
        if (enabled) currentBlockEntityId = blockEntityId;
        return previous;
    }

    /** Marks the LevelRenderer interval in which world entities are submitted. */
    public static void beginWorldSubmissionWindow() {
        // Reserve the session-stable source before ModelFeatureRenderer moves
        // submissions into the separate batch. If allocation fails, the host
        // batch remains untouched and can render the entities normally.
        worldSubmissionWindow = enabled
                && (entityPipeline != null || translucentEntityPipeline != null
                || spiderEyesPipeline != null || blockPipeline != null
                || damagedBlockPipeline != null || shadowCasterPipeline != null)
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
        submissionFamily = SubmissionIdentity.FAMILY_NONE;
    }

    public static boolean isWorldSubmissionWindow() {
        return enabled && worldSubmissionWindow;
    }

    /** True only when the separate entity batch can be flushed safely. */
    public static boolean isEntityBufferReady() {
        return enabled && (entityPipeline != null || translucentEntityPipeline != null
                || spiderEyesPipeline != null || blockPipeline != null
                || damagedBlockPipeline != null || isShadowCasterPass())
                && entityBufferSource != null;
    }

    public static boolean supportsSubmissionFamily(int family) {
        if (isShadowCasterPass()) {
            return family == SubmissionIdentity.FAMILY_ENTITY || family == SubmissionIdentity.FAMILY_BLOCK;
        }
        return switch (family) {
            case SubmissionIdentity.FAMILY_ENTITY -> entityPipeline != null
                    || translucentEntityPipeline != null || spiderEyesPipeline != null;
            case SubmissionIdentity.FAMILY_BLOCK -> blockPipeline != null
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

    /**
     * The discard threshold the pack program tests this draw against, as Iris does with
     * iris_currentAlphaTest: a host-layout lane's own Iris ShaderKey test, else the host
     * pipeline's ({@link HostAlphaTest}).
     */
    public static float alphaReference(RenderPipeline pipeline) {
        HostLayoutLane lane = isDrawActive() ? HostLayoutLane.of(activeFamily) : null;
        if (lane != null) return lane.alphaReference();
        return HostAlphaTest.reference(pipeline, isShadowCasterPass(),
                activeFamily == Family.HAND || activeFamily == Family.HAND_WATER);
    }

    public static boolean supportsWorldPipeline(RenderPipeline pipeline) {
        if (pipeline != null && isShadowCasterPass()) {
            // Iris maps every entity pipeline to SHADOW_ENTITIES_CUTOUT in the shadow pass.
            return pipeline.getVertexFormat() == DefaultVertexFormat.NEW_ENTITY;
        }
        return pipeline != null && SUPPORTED_WORLD_PIPELINES.contains(pipeline);
    }

    /**
     * Installs the text layout variants after {@link #install}: Iris draws TEXT with
     * gbuffers_entities_translucent and a block entity's TEXT_BE with gbuffers_block_translucent.
     */
    public static void installTextVariants(PackPipelines.PackEntity translucentEntityText,
                                           PackPipelines.PackEntity blockTranslucentText) {
        translucentEntityTextPipeline = translucentEntityText == null ? null : translucentEntityText.pipeline();
        blockTranslucentTextPipeline = blockTranslucentText == null ? null : blockTranslucentText.pipeline();
    }

    /**
     * The glyph pipelines Iris routes through getText: world, sign and see-through text. Intensity
     * text stays host-drawn: Iris swizzles its one-channel glyph texture to RRRR, a texture state
     * Chimera has no counterpart for, and only TrueType resource-pack fonts use it.
     */
    public static boolean isTextPipeline(RenderPipeline pipeline) {
        return pipeline == RenderPipelines.TEXT || pipeline == RenderPipelines.TEXT_POLYGON_OFFSET
                || pipeline == RenderPipelines.TEXT_SEE_THROUGH;
    }

    private static boolean isGlyphFormat(VertexFormat format) {
        return format == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP
                || format == ChimeraVertexFormats.EXTENDED_GLYPH;
    }

    private static GraphicsPipeline textPipelineFor(Family family) {
        if (family == null) return null;
        return switch (family) {
            case ENTITY_TRANSLUCENT -> translucentEntityTextPipeline;
            case BLOCK_TRANSLUCENT -> blockTranslucentTextPipeline;
            default -> null;
        };
    }

    /** Selects the most specific installed entity lane for one host render type. */
    public static Family familyForRenderType(Family base, RenderType renderType) {
        if (isShadowCasterPass()) {
            // One shadow program for every entity-format caster. A batch on another layout
            // (text, leads, lines, lightning, glint) draws through its HostLayoutLane shadow lane.
            return renderType != null && renderType.format() == DefaultVertexFormat.NEW_ENTITY
                    ? Family.SHADOW_ENTITIES : Family.HOST_FALLBACK;
        }
        if (renderType != null && isTextPipeline(renderType.pipeline())) {
            // Iris IrisPipelines.getText: TEXT_BE (gbuffers_block_translucent) inside a block entity,
            // TEXT (gbuffers_entities_translucent) otherwise. Each draws in its program's own targets.
            Family text = base == Family.BLOCK ? Family.BLOCK_TRANSLUCENT : Family.ENTITY_TRANSLUCENT;
            return textPipelineFor(text) != null ? text : Family.HOST_FALLBACK;
        }
        if (base == Family.ENTITY && renderType != null) {
            return entityFamilyFor(renderType.pipeline());
        }
        return base;
    }

    /** Moves an ENTITY batch to the translucent or eyes lane its host pipeline belongs to. */
    private static Family entityFamilyFor(RenderPipeline pipeline) {
        if (pipeline == RenderPipelines.ENTITY_TRANSLUCENT
                || pipeline == RenderPipelines.ITEM_ENTITY_TRANSLUCENT_CULL) {
            return translucentEntityPipeline != null ? Family.ENTITY_TRANSLUCENT : Family.HOST_FALLBACK;
        }
        // Iris IrisPipelines: EYES -> ENTITIES_EYES and ENTITY_TRANSLUCENT_EMISSIVE ->
        // ENTITIES_EYES_TRANS, both ProgramId.SpiderEyes (gbuffers_entities_glowing is unused).
        if (pipeline == RenderPipelines.EYES || pipeline == RenderPipelines.ENTITY_TRANSLUCENT_EMISSIVE) {
            return spiderEyesPipeline != null ? Family.SPIDER_EYES : Family.HOST_FALLBACK;
        }
        return Family.ENTITY;
    }

    /**
     * Keeps the Vulkan substitution limited to a host pipeline whose vertex
     * contract matches the active family. The hand hook also surrounds arm,
     * map, and other first-person draws, so a pack hand pipeline must never
     * replace one of those unrelated host formats.
     */
    public static boolean shouldUsePackPipeline(RenderPipeline pipeline) {
        // Re-select within the scope even after a preceding draw used host fallback.
        if (!enabled || !drawActive || pipeline == null) {
            return false;
        }
        selectParticleFamily(pipeline);
        selectHandVariant(pipeline);
        if (activePipeline == null) return false;
        VertexFormat format = pipeline.getVertexFormat();
        if (isGlyphFormat(format) && isTextPipeline(pipeline)
                && (activeFamily == Family.ENTITY_TRANSLUCENT || activeFamily == Family.BLOCK_TRANSLUCENT)) {
            // Text draws in its family's window and outputs; only its native layout differs.
            GraphicsPipeline text = textPipelineFor(activeFamily);
            if (text == null) return false;
            activePipeline = text;
            return true;
        }
        return activeFamilyAccepts(pipeline, format);
    }

    /** True when the active family's pack pipeline can draw this host pipeline and vertex format. */
    private static boolean activeFamilyAccepts(RenderPipeline pipeline, VertexFormat format) {
        return switch (activeFamily) {
            case DAMAGED_BLOCK -> pipeline == RenderPipelines.CRUMBLING
                    && format == DefaultVertexFormat.BLOCK;
            case ENTITY, ENTITY_TRANSLUCENT, SPIDER_EYES, BLOCK, SHADOW_ENTITIES ->
                    format == DefaultVertexFormat.NEW_ENTITY
                    || format == ChimeraVertexFormats.EXTENDED_ENTITY;
            case HAND, HAND_WATER -> ChimeraVertexFormats.handHostFormat(format) != null;
            case PARTICLE, PARTICLE_TRANSLUCENT -> format == DefaultVertexFormat.PARTICLE;
            case WEATHER, LINE, GLINT, HAND_GLINT, LIGHTNING, BEACON_BEAM, LEASH, TEXT_BG,
                 SHADOW_LINES, SHADOW_LEASH, SHADOW_LIGHTNING, SHADOW_GLINT, SHADOW_TEXT, SHADOW_TEXT_BG,
                 SHADOW_BASIC -> HostLayoutLane.of(activeFamily).serves(pipeline);
            // Block-entity text is the only draw of its family, admitted above.
            case BLOCK_TRANSLUCENT, HOST_FALLBACK -> false;
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

    /** Crumbling keeps BLOCK but still needs the host draw's matrix slices. */
    public static boolean requiresHostTransforms() {
        return isDrawActive() && (widensVertexFormat(activeFamily) || activeFamily == Family.DAMAGED_BLOCK);
    }

    /** Families that append pack inputs to the host format instead of drawing it directly. */
    private static boolean widensVertexFormat(Family family) {
        return switch (family) {
            case ENTITY, ENTITY_TRANSLUCENT, SPIDER_EYES, BLOCK, BLOCK_TRANSLUCENT, HAND, HAND_WATER,
                 SHADOW_ENTITIES -> true;
            case DAMAGED_BLOCK, PARTICLE, PARTICLE_TRANSLUCENT, WEATHER, LINE, GLINT, HAND_GLINT,
                 LIGHTNING, BEACON_BEAM, LEASH, TEXT_BG, SHADOW_LINES, SHADOW_LEASH, SHADOW_LIGHTNING, SHADOW_GLINT,
                 SHADOW_TEXT, SHADOW_TEXT_BG, SHADOW_BASIC, HOST_FALLBACK -> false;
        };
    }

    /** Returns the append-only format for the active family when its host format matches. */
    public static VertexFormat extendedFormat(VertexFormat format) {
        if (enabled && drawActive && (requestedFamily == Family.HAND || requestedFamily == Family.HAND_WATER)
                && format != null) {
            return ChimeraVertexFormats.handFormats().getOrDefault(format, format);
        }
        if (!isDrawActive() || format == null) {
            return format;
        }
        if (format == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP && textPipelineFor(activeFamily) != null) {
            return ChimeraVertexFormats.EXTENDED_GLYPH;
        }
        return switch (activeFamily) {
            case ENTITY, ENTITY_TRANSLUCENT, SPIDER_EYES, BLOCK, SHADOW_ENTITIES ->
                    format == DefaultVertexFormat.NEW_ENTITY
                    ? ChimeraVertexFormats.EXTENDED_ENTITY : format;
            case HAND, HAND_WATER -> ChimeraVertexFormats.handFormats().getOrDefault(format, format);
            case BLOCK_TRANSLUCENT, DAMAGED_BLOCK, PARTICLE, PARTICLE_TRANSLUCENT, WEATHER, LINE, GLINT,
                 HAND_GLINT, LIGHTNING, BEACON_BEAM, LEASH, TEXT_BG, SHADOW_LINES, SHADOW_LEASH, SHADOW_LIGHTNING,
                 SHADOW_GLINT, SHADOW_TEXT, SHADOW_TEXT_BG, SHADOW_BASIC -> format;
            case HOST_FALLBACK -> format;
        };
    }

    /** True when the active family needs an append-only format. */
    public static boolean shouldExtendEntityFormat(VertexFormat format) {
        return extendedFormat(format) != format;
    }

    private static void restoreTextures() {
        if (!textureSnapshot) {
            return;
        }
        for (int index = 0; index < VTextureSelector.SIZE; index++) {
            ChimeraTextureBindingState.clearPackBinding(index);
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
                && spiderEyesPipeline == null && blockPipeline == null
                && damagedBlockPipeline == null && shadowCasterPipeline == null)) {
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
            previousSubmissionFamilies[index] = SubmissionIdentity.FAMILY_NONE;
            previousModelEntityIds[index] = 0;
            previousBlockEntityIds[index] = 0;
            previousModelBlockEntityIds[index] = 0;
        }
        currentBlockEntityId = 0;
    }

    private static void popSubmission() {
        if (submittingDepth <= 0) {
            submissionFamily = SubmissionIdentity.FAMILY_NONE;
            currentEntityId = 0;
            currentBlockEntityId = 0;
            return;
        }
        submittingDepth--;
        currentEntityId = submittingDepth == 0
                ? 0
                : submittingDepth < previousEntityIds.length
                ? previousEntityIds[submittingDepth] : 0;
        currentBlockEntityId = submittingDepth == 0
                ? 0
                : submittingDepth < previousBlockEntityIds.length
                ? previousBlockEntityIds[submittingDepth] : 0;
        submissionFamily = submittingDepth == 0
                ? SubmissionIdentity.FAMILY_NONE
                : submittingDepth < previousSubmissionFamilies.length
                ? previousSubmissionFamilies[submittingDepth]
                : SubmissionIdentity.FAMILY_NONE;
    }

    private static int submissionFamily = SubmissionIdentity.FAMILY_NONE;

    private static GraphicsPipeline pipelineFor(Family family) {
        return switch (family) {
            case ENTITY -> entityPipeline;
            case ENTITY_TRANSLUCENT -> translucentEntityPipeline;
            case SPIDER_EYES -> spiderEyesPipeline;
            case BLOCK -> blockPipeline;
            case BLOCK_TRANSLUCENT -> blockTranslucentTextPipeline;
            case DAMAGED_BLOCK -> damagedBlockPipeline;
            case HAND -> handPipeline;
            case HAND_WATER -> handWaterPipeline;
            case PARTICLE -> particlePipeline;
            case PARTICLE_TRANSLUCENT -> translucentParticlePipeline;
            case WEATHER, LINE, GLINT, HAND_GLINT, LIGHTNING, BEACON_BEAM, LEASH, TEXT_BG,
                 SHADOW_LINES, SHADOW_LEASH, SHADOW_LIGHTNING, SHADOW_GLINT, SHADOW_TEXT, SHADOW_TEXT_BG,
                 SHADOW_BASIC -> hostLayoutPipelines.get(family);
            case SHADOW_ENTITIES -> shadowCasterPass ? shadowCasterPipeline : null;
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

    /** Picks the hand variant built for the host draw's vertex format, or host fallback. */
    private static void selectHandVariant(RenderPipeline hostPipeline) {
        if (requestedFamily != Family.HAND && requestedFamily != Family.HAND_WATER) {
            return;
        }
        var variants = requestedFamily == Family.HAND ? handVariants : handWaterVariants;
        var selected = variants == null ? null : variants.forFormat(hostPipeline.getVertexFormat());
        activeFamily = selected == null ? Family.HOST_FALLBACK : requestedFamily;
        activePipeline = selected == null ? null : selected.pipeline();
    }

    private static String status(GraphicsPipeline value) {
        return value == null ? "fallback" : "installed";
    }
}
