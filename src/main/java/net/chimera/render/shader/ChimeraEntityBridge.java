package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.ChimeraMod;
import net.chimera.render.ChimeraHandRenderer;
import net.chimera.render.ChimeraTextureBindingState;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.shaderpack.PackEntityIdResolver;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.renderer.RenderType;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * Render-thread state of the pack geometry families: which pack program draws which host draw.
 *
 * <p>Two kinds of lane exist. A family lane (entities, block entities, their text, the hand and
 * shadow casters) widens the host vertex format with Iris's attributes, so its geometry is batched
 * apart from the host's in a {@link FamilyRenderType} the moment it is written: the lane is chosen
 * from the render type and the context drawing it (EntityRenderDispatcher, BlockEntityRenderDispatcher,
 * the hand, the shadow casters). A host-layout lane (particles, crumbling, and the
 * {@link HostLayoutLane}s) keeps the host mesh and is chosen when it draws, by shader name.</p>
 *
 * <p>Either way the draw is open here while it runs, and the ShaderInstance pipeline seam
 * (ChimeraShaderPipelineMixin) hands Reforged the open draw's pack pipeline.</p>
 */
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
        SHADOW_BASIC
    }

    /** Opaque and cutout entity render types, by vanilla name (Iris ENTITIES_SOLID and ENTITIES_CUTOUT). */
    private static final Set<String> ENTITY_TYPES = Set.of(
            "entity_solid", "entity_cutout", "entity_cutout_no_cull", "entity_cutout_no_cull_z_offset",
            "entity_smooth_cutout", "entity_no_outline");
    /**
     * Translucent entity render types (Iris ENTITIES_TRANSLUCENT). Flat held and dropped items draw
     * with item_entity_translucent_cull: drawn by the host they wrote no gbuffer data, and deferred
     * lighting erased them to a ghost.
     */
    private static final Set<String> TRANSLUCENT_TYPES = Set.of(
            "entity_translucent", "entity_translucent_cull", "item_entity_translucent_cull");
    /** Iris ENTITIES_EYES and ENTITIES_EYES_TRANS, both ProgramId.SpiderEyes. */
    private static final Set<String> EYES_TYPES = Set.of("eyes", "entity_translucent_emissive");
    /**
     * The glyph render types Iris routes through TEXT and TEXT_BE: world, sign and see-through text.
     * Intensity text stays host-drawn: Iris swizzles its one-channel glyph texture to RRRR, a texture
     * state Chimera has no counterpart for, and only TrueType resource-pack fonts use it.
     */
    private static final Set<String> TEXT_TYPES = Set.of("text", "text_polygon_offset", "text_see_through");

    private static PackEntityIdResolver resolver = PackEntityIdResolver.empty();
    private static GraphicsPipeline entityPipeline;
    private static GraphicsPipeline translucentEntityPipeline;
    private static GraphicsPipeline spiderEyesPipeline;
    private static GraphicsPipeline blockPipeline;
    private static GraphicsPipeline damagedBlockPipeline;
    private static PackPipelines.PackHand handVariants;
    private static PackPipelines.PackHand handWaterVariants;
    /** The translucent-entity and block-translucent programs built for Iris's GLYPH text layout. */
    private static GraphicsPipeline translucentEntityTextPipeline;
    private static GraphicsPipeline blockTranslucentTextPipeline;
    private static GraphicsPipeline particlePipeline;
    private static GraphicsPipeline translucentParticlePipeline;
    /** The installed lanes of {@link HostLayoutLane}, keyed by their family. */
    private static final EnumMap<Family, GraphicsPipeline> hostLayoutPipelines = new EnumMap<>(Family.class);
    /** shadow_entities, drawing every entity-format caster in the shadow pass. */
    private static GraphicsPipeline shadowCasterPipeline;
    private static boolean enabled;

    /** True from LevelRenderer.renderLevel's head to its return while the pack renders the world. */
    private static boolean worldWindow;
    /** True while the shadow pass draws its casters (Iris ShadowRenderer.ACTIVE). */
    private static boolean shadowCasterPass;
    private static boolean particleWindow;
    private static boolean particleTranslucent;
    private static boolean weatherWindow;

    /** The entity or block entity being rendered, innermost last; ENTITY or BLOCK. */
    private static final Family[] contexts = new Family[8];
    private static final int[] previousEntityIds = new int[8];
    private static final int[] previousBlockEntityIds = new int[8];
    private static int contextDepth;
    private static int currentEntityId;
    /** Iris CapturedRenderingState current block entity: EntityIds.y, read as blockEntityId. */
    private static int currentBlockEntityId;

    private static boolean drawActive;
    static Family activeFamily;
    private static GraphicsPipeline activePipeline;
    private static float activeAlphaReference;
    private static PackRenderingPhase activePhase = PackRenderingPhase.NONE;
    private static final VulkanImage[] previousTextures = new VulkanImage[VTextureSelector.SIZE];

    private ChimeraEntityBridge() {}

    /** Installs the family lanes; the other lanes follow through the install methods below. */
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
        disable();
        entityPipeline = pipeline(entity);
        translucentEntityPipeline = pipeline(translucentEntity);
        spiderEyesPipeline = pipeline(spiderEyes);
        blockPipeline = pipeline(block);
        damagedBlockPipeline = pipeline(damagedBlock);
        handVariants = hand;
        handWaterVariants = handWater;
        particlePipeline = particle == null ? null : particle.pipeline();
        translucentParticlePipeline = translucentParticle == null ? null : translucentParticle.pipeline();
        resolver = nextResolver == null ? PackEntityIdResolver.empty() : nextResolver;
        ChimeraMod.LOGGER.info("[chimera] family bridge: entity={}, entityTranslucent={}, spiderEyes={}, "
                        + "block={}, damagedBlock={}, hand={}, handWater={}, particle={}, particleTranslucent={}",
                status(entityPipeline), status(translucentEntityPipeline), status(spiderEyesPipeline),
                status(blockPipeline), status(damagedBlockPipeline), status(hand == null ? null : hand.primary()),
                status(handWater == null ? null : handWater.primary()), status(particlePipeline),
                status(translucentParticlePipeline));
    }

    /**
     * Installs the text layout variants after {@link #install}: Iris draws TEXT with
     * gbuffers_entities_translucent and a block entity's TEXT_BE with gbuffers_block_translucent.
     */
    public static void installTextVariants(PackPipelines.PackEntity translucentEntityText,
                                           PackPipelines.PackEntity blockTranslucentText) {
        translucentEntityTextPipeline = pipeline(translucentEntityText);
        blockTranslucentTextPipeline = pipeline(blockTranslucentText);
    }

    /** Installs the host-layout lanes after {@link #install}. A lane without a pipeline keeps its host draw. */
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
        shadowCasterPipeline = pipeline(casters);
        ChimeraMod.LOGGER.info("[chimera] family bridge: shadow casters={}", status(shadowCasterPipeline));
    }

    public static void setEnabled(boolean value) {
        enabled = value && isInstalled();
        if (!enabled) resetFrameState();
    }

    /** Uninstalls every lane and frees the family batches built for them. */
    public static void disable() {
        enabled = false;
        resetFrameState();
        entityPipeline = null;
        translucentEntityPipeline = null;
        spiderEyesPipeline = null;
        blockPipeline = null;
        damagedBlockPipeline = null;
        handVariants = null;
        handWaterVariants = null;
        translucentEntityTextPipeline = null;
        blockTranslucentTextPipeline = null;
        particlePipeline = null;
        translucentParticlePipeline = null;
        hostLayoutPipelines.clear();
        shadowCasterPipeline = null;
        resolver = PackEntityIdResolver.empty();
        FamilyBufferSource.releaseAll();
        FamilyRenderType.clear();
        FamilyDrawTrace.reset();
    }

    public static boolean isInstalled() {
        return entityPipeline != null || translucentEntityPipeline != null || spiderEyesPipeline != null
                || blockPipeline != null || damagedBlockPipeline != null || handVariants != null
                || handWaterVariants != null || particlePipeline != null || translucentParticlePipeline != null
                || !hostLayoutPipelines.isEmpty() || shadowCasterPipeline != null;
    }

    private static void resetFrameState() {
        restoreTextures();
        drawActive = false;
        activePipeline = null;
        activeFamily = null;
        worldWindow = false;
        shadowCasterPass = false;
        particleWindow = false;
        weatherWindow = false;
        contextDepth = 0;
        currentEntityId = 0;
        currentBlockEntityId = 0;
    }

    // ------------------------------------------------------------------
    // Windows and contexts
    // ------------------------------------------------------------------

    /** Opens the world window at LevelRenderer.renderLevel's head; world buffers route families inside it. */
    public static void beginWorldWindow() {
        worldWindow = enabled;
        contextDepth = 0;
    }

    public static void endWorldWindow() {
        worldWindow = false;
        contextDepth = 0;
        currentEntityId = 0;
        currentBlockEntityId = 0;
    }

    /** True while RenderBuffers' sources route family render types (ChimeraRenderBuffersMixin). */
    public static boolean routesWorldBuffers() {
        return enabled && worldWindow && !drawActive;
    }

    /**
     * Opens the shadow pass's caster window around its entity and block-entity drawing (Iris
     * ShadowRenderer.ACTIVE): every entity-format batch draws with shadow_entities, every
     * host-layout draw with its shadow lane, and anything else stays out of the shadow map.
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

    /** Opens the particle window around ParticleEngine.render. */
    public static void beginParticles() {
        particleWindow = enabled && worldWindow;
    }

    public static void endParticles() {
        particleWindow = false;
    }

    /** The particle render type ParticleEngine is about to draw: Iris splits particles by translucency. */
    public static void setParticleTranslucent(boolean translucent) {
        particleTranslucent = translucent;
    }

    /** Opens the weather window around LevelRenderer.renderSnowAndRain. */
    public static void beginWeather() {
        weatherWindow = enabled && worldWindow;
    }

    public static void endWeather() {
        weatherWindow = false;
    }

    /** Opens an entity's context for what EntityRenderDispatcher.render draws, with its entity.properties id. */
    public static void beginEntity(String name) {
        push(Family.ENTITY, resolver.resolveName(name), 0);
    }

    /**
     * Opens a block entity's context. Like Iris MixinBlockEntityRenderDispatcher, the block state's
     * block.properties id becomes blockEntityId for everything the block entity draws.
     */
    public static void beginBlockEntity(int blockEntityId) {
        push(Family.BLOCK, 0, blockEntityId);
    }

    /** Closes the context {@link #beginEntity} or {@link #beginBlockEntity} opened. */
    public static void endContext() {
        if (contextDepth <= 0) return;
        contextDepth--;
        boolean saved = contextDepth < contexts.length;
        currentEntityId = saved ? previousEntityIds[contextDepth] : 0;
        currentBlockEntityId = saved ? previousBlockEntityIds[contextDepth] : 0;
    }

    private static void push(Family context, int entityId, int blockEntityId) {
        if (contextDepth < contexts.length) {
            contexts[contextDepth] = context;
            previousEntityIds[contextDepth] = currentEntityId;
            previousBlockEntityIds[contextDepth] = currentBlockEntityId;
        }
        contextDepth++;
        currentEntityId = entityId;
        currentBlockEntityId = blockEntityId;
    }

    private static Family context() {
        return contextDepth == 0 ? null : contexts[Math.min(contextDepth, contexts.length) - 1];
    }

    public static int currentEntityId() {
        return enabled ? currentEntityId : 0;
    }

    public static int currentBlockEntityId() {
        return enabled ? currentBlockEntityId : 0;
    }

    /** Iris setCurrentEntity: replaces the current entity id and returns the one replaced. */
    public static int replaceEntityId(int entityId) {
        int previous = currentEntityId;
        if (enabled) currentEntityId = entityId;
        return previous;
    }

    /** Iris setCurrentBlockEntity: replaces the current block entity id and returns the one replaced. */
    public static int replaceBlockEntityId(int blockEntityId) {
        int previous = currentBlockEntityId;
        if (enabled) currentBlockEntityId = blockEntityId;
        return previous;
    }

    /** The entity id Iris gives burning-entity fire: the pack's entity.properties id for minecraft:entity_flame. */
    public static int flameEntityId() {
        return resolver.resolveName("minecraft:entity_flame");
    }

    // ------------------------------------------------------------------
    // Lane selection
    // ------------------------------------------------------------------

    /**
     * The family lane that batches {@code type} where it is being written, or null when the host
     * draws it. A lane is chosen only when its pipeline is installed, so a widened batch always has
     * a program to draw it.
     */
    public static Family familyFor(RenderType type) {
        if (!enabled || drawActive || type == null) return null;
        VertexFormat format = type.format();
        if (shadowCasterPass) {
            return format == DefaultVertexFormat.NEW_ENTITY && shadowCasterPipeline != null
                    ? Family.SHADOW_ENTITIES : null;
        }
        if (ChimeraHandRenderer.active()) {
            Family hand = ChimeraHandRenderer.translucentPhase() ? Family.HAND_WATER : Family.HAND;
            return handPipeline(hand, ChimeraVertexFormats.handFormats().getOrDefault(format, format)) != null
                    ? hand : null;
        }
        Family context = worldWindow ? context() : null;
        if (context == null) return null;
        if (TEXT_TYPES.contains(type.name) && format == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP) {
            // Iris getText: TEXT_BE (gbuffers_block_translucent) inside a block entity, TEXT
            // (gbuffers_entities_translucent) otherwise.
            Family text = context == Family.BLOCK ? Family.BLOCK_TRANSLUCENT : Family.ENTITY_TRANSLUCENT;
            return textPipeline(text) != null ? text : null;
        }
        if (format != DefaultVertexFormat.NEW_ENTITY) return null;
        if (context == Family.BLOCK) {
            return blockPipeline != null && (ENTITY_TYPES.contains(type.name) || TRANSLUCENT_TYPES.contains(type.name))
                    ? Family.BLOCK : null;
        }
        if (TRANSLUCENT_TYPES.contains(type.name)) {
            return translucentEntityPipeline != null ? Family.ENTITY_TRANSLUCENT : null;
        }
        if (EYES_TYPES.contains(type.name)) return spiderEyesPipeline != null ? Family.SPIDER_EYES : null;
        return ENTITY_TYPES.contains(type.name) && entityPipeline != null ? Family.ENTITY : null;
    }

    /** The vertex format a family lane writes for a host format: Iris's attributes appended. */
    static VertexFormat widenedFormat(Family family, VertexFormat format) {
        return switch (family) {
            case HAND, HAND_WATER -> ChimeraVertexFormats.handFormats().getOrDefault(format, format);
            case ENTITY_TRANSLUCENT, BLOCK_TRANSLUCENT -> format == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP
                    ? ChimeraVertexFormats.EXTENDED_GLYPH : widenedEntity(format);
            case ENTITY, SPIDER_EYES, BLOCK, SHADOW_ENTITIES -> widenedEntity(format);
            default -> format;
        };
    }

    private static VertexFormat widenedEntity(VertexFormat format) {
        return format == DefaultVertexFormat.NEW_ENTITY ? ChimeraVertexFormats.EXTENDED_ENTITY : format;
    }

    /**
     * The host-layout lane that draws a host mesh now, by its shader, or null when the host draws
     * it: a {@link HostLayoutLane} of the current pass, a particle, or the crumbling overlay.
     */
    public static Family hostLaneFor(String shader, VertexFormat format) {
        if (!enabled || drawActive || shader == null) return null;
        HostLayoutLane.Pass pass = shadowCasterPass ? HostLayoutLane.Pass.SHADOW
                : ChimeraHandRenderer.active() ? HostLayoutLane.Pass.HAND
                : !worldWindow ? null
                : weatherWindow ? HostLayoutLane.Pass.WEATHER : HostLayoutLane.Pass.WORLD;
        if (pass == null) return null;
        HostLayoutLane lane = HostLayoutLane.forDraw(shader, format, pass);
        if (lane != null) return hostLayoutPipelines.containsKey(lane.family()) ? lane.family() : null;
        if (pass != HostLayoutLane.Pass.WORLD) return null;
        if (particleWindow && shader.equals("particle") && format == DefaultVertexFormat.PARTICLE) {
            // An opaque pack shader never draws translucent particles, nor the other way round:
            // the host state and ordering are part of each lane's contract.
            if (particleTranslucent) return translucentParticlePipeline != null ? Family.PARTICLE_TRANSLUCENT : null;
            return particlePipeline != null ? Family.PARTICLE : null;
        }
        return shader.equals("rendertype_crumbling") && format == DefaultVertexFormat.BLOCK
                && damagedBlockPipeline != null ? Family.DAMAGED_BLOCK : null;
    }

    /**
     * True for a shadow-pass draw no shadow lane serves. Iris draws every shadow-pass draw with a
     * shadow program; this one stays out of the shadow map rather than writing colour and depth
     * with its host shader.
     */
    public static boolean dropsHostDraw(String shader) {
        if (!isShadowCasterPass() || drawActive) return false;
        FamilyDrawTrace.noteShadowDropped(shader);
        return true;
    }

    // ------------------------------------------------------------------
    // Draws
    // ------------------------------------------------------------------

    /**
     * Opens a draw of {@code family} on {@code format} with the host shader {@code shader}; false
     * when the lane has no pipeline for that format.
     */
    public static boolean beginDraw(Family family, VertexFormat format, String shader) {
        if (!enabled || drawActive) return false;
        GraphicsPipeline selected = pipelineFor(family, format);
        if (selected == null) return false;
        HostLayoutLane lane = HostLayoutLane.of(family);
        activeFamily = family;
        activePipeline = selected;
        activeAlphaReference = lane != null ? lane.alphaReference()
                : HostAlphaTest.reference(shader, family, shadowCasterPass);
        activePhase = lane != null ? lane.phase() : phase(family);
        for (int index = 0; index < VTextureSelector.SIZE; index++) {
            previousTextures[index] = VTextureSelector.getBoundTexture(index);
        }
        drawActive = true;
        FamilyDrawTrace.noteDraw(family, shader, format);
        return true;
    }

    public static void endDraw() {
        restoreTextures();
        drawActive = false;
        activePipeline = null;
        activeFamily = null;
    }

    public static boolean isDrawActive() {
        return enabled && drawActive;
    }

    public static Family activeFamily() {
        return activeFamily;
    }

    /** The open draw's pack pipeline, or null when no draw is open. */
    public static GraphicsPipeline pipeline() {
        return isDrawActive() ? activePipeline : null;
    }

    /** The discard threshold the open draw's program tests against (Iris iris_currentAlphaTest). */
    public static float alphaReference() {
        return activeAlphaReference;
    }

    /** The Iris rendering phase (renderStage) of the open draw. */
    public static PackRenderingPhase renderingPhase() {
        return activePhase;
    }

    /** Iris ExtendedShader binds a white texture to unit 0 for a host format without texture coordinates. */
    public static boolean whiteAlbedo() {
        HostLayoutLane lane = activeFamily == null ? null : HostLayoutLane.of(activeFamily);
        return lane != null && lane.whiteAlbedo();
    }

    /** The pack pipeline a lane draws {@code format} with, or null when it has none installed. */
    public static GraphicsPipeline lanePipeline(Family family, VertexFormat format) {
        return pipelineFor(family, format);
    }

    private static GraphicsPipeline pipelineFor(Family family, VertexFormat format) {
        return switch (family) {
            case ENTITY -> entityPipeline;
            case ENTITY_TRANSLUCENT -> format == ChimeraVertexFormats.EXTENDED_GLYPH
                    ? translucentEntityTextPipeline : translucentEntityPipeline;
            case SPIDER_EYES -> spiderEyesPipeline;
            case BLOCK -> blockPipeline;
            case BLOCK_TRANSLUCENT -> blockTranslucentTextPipeline;
            case DAMAGED_BLOCK -> damagedBlockPipeline;
            case HAND, HAND_WATER -> handPipeline(family, format);
            case PARTICLE -> particlePipeline;
            case PARTICLE_TRANSLUCENT -> translucentParticlePipeline;
            case SHADOW_ENTITIES -> shadowCasterPass ? shadowCasterPipeline : null;
            default -> hostLayoutPipelines.get(family);
        };
    }

    private static GraphicsPipeline handPipeline(Family family, VertexFormat format) {
        var variants = family == Family.HAND ? handVariants : handWaterVariants;
        var variant = variants == null ? null : variants.forFormat(format);
        return variant == null ? null : variant.pipeline();
    }

    private static GraphicsPipeline textPipeline(Family family) {
        return family == Family.BLOCK_TRANSLUCENT ? blockTranslucentTextPipeline : translucentEntityTextPipeline;
    }

    /** The Iris rendering phase a family outside the host-layout lanes draws in. */
    private static PackRenderingPhase phase(Family family) {
        return switch (family) {
            // Iris keeps the ENTITIES phase for every entity and block-entity shadow caster.
            case ENTITY, ENTITY_TRANSLUCENT, SPIDER_EYES, SHADOW_ENTITIES -> PackRenderingPhase.ENTITIES;
            case BLOCK, BLOCK_TRANSLUCENT -> PackRenderingPhase.BLOCK_ENTITIES;
            case DAMAGED_BLOCK -> PackRenderingPhase.DESTROY;
            case HAND -> PackRenderingPhase.HAND_SOLID;
            case HAND_WATER -> PackRenderingPhase.HAND_TRANSLUCENT;
            case PARTICLE, PARTICLE_TRANSLUCENT -> PackRenderingPhase.PARTICLES;
            default -> PackRenderingPhase.NONE;
        };
    }

    private static void restoreTextures() {
        if (!drawActive) return;
        for (int index = 0; index < VTextureSelector.SIZE; index++) {
            ChimeraTextureBindingState.clearPackBinding(index);
            VTextureSelector.bindTexture(index, previousTextures[index]);
            previousTextures[index] = null;
        }
    }

    private static GraphicsPipeline pipeline(PackPipelines.PackEntity entity) {
        return entity == null ? null : entity.pipeline();
    }

    private static String status(Object value) {
        return value == null ? "fallback" : "installed";
    }

}
