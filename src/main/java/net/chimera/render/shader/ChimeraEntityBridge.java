package net.chimera.render.shader;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.ChimeraMod;
import net.chimera.mixin.ChimeraCompositeRenderTypeAccessor;
import net.chimera.mixin.ChimeraCompositeStateAccessor;
import net.chimera.mixin.ChimeraShaderStateShardAccessor;
import net.chimera.render.ChimeraHandRenderer;
import net.chimera.render.ChimeraTextureBindingState;
import net.chimera.render.vertex.ChimeraVertexFormats;
import net.chimera.shaderpack.PackEntityIdResolver;
import net.chimera.shaderpack.PackPipelines;
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Render-thread state of the pack geometry families: which pack program draws which host draw.
 *
 * <p>Two kinds of lane exist. A family lane (entities, block entities, their text and moving blocks,
 * the hand and shadow casters) widens the host vertex format with Iris's attributes, so its
 * geometry is batched apart from the host's in a {@link FamilyRenderType} the moment it is written:
 * the lane is chosen from the render type and the context drawing it (EntityRenderDispatcher,
 * BlockEntityRenderDispatcher, the hand, the shadow casters). A host-layout lane (particles,
 * crumbling, and the {@link HostLayoutLane}s) keeps the host mesh and is chosen when it draws, by
 * shader name.</p>
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
        /**
         * A block entity's translucent models (Iris BE_TRANSLUCENT) and text (TEXT_BE), drawn with
         * gbuffers_block_translucent.
         */
        BLOCK_TRANSLUCENT,
        /**
         * Falling blocks and moving pistons: chunk render types drawn by an entity or a block entity,
         * with gbuffers_block on the block layout (Iris MOVING_BLOCK).
         */
        MOVING_BLOCK,
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
        /**
         * The shadow pass's entity-format casters (Iris SHADOW_ENTITIES_CUTOUT), and on the block
         * layout its moving blocks.
         */
        SHADOW_ENTITIES,
        /** A block entity's models in the shadow pass, drawn with shadow_block (Iris SHADOW_BLOCK). */
        SHADOW_BLOCK,
        /** The shadow pass's host-layout lanes (HostLayoutLane, Pass.SHADOW). */
        SHADOW_LINES,
        SHADOW_LEASH,
        SHADOW_LIGHTNING,
        SHADOW_GLINT,
        SHADOW_TEXT,
        SHADOW_TEXT_BG,
        SHADOW_BASIC
    }

    /**
     * How Iris 1.21.1's MixinGameRenderer keys an entity-format shader, in an entity, in a block
     * entity and in the shadow pass.
     */
    enum EntityShader {
        /** ENTITIES_SOLID_DIFFUSE or ENTITIES_CUTOUT_DIFFUSE; BLOCK_ENTITY_DIFFUSE; SHADOW_BLOCK. */
        MODEL,
        /** ENTITIES_CUTOUT; BLOCK_ENTITY; SHADOW_ENTITIES_CUTOUT. */
        OVERLAY,
        /** ENTITIES_TRANSLUCENT; BE_TRANSLUCENT; SHADOW_BLOCK. */
        TRANSLUCENT,
        /** ENTITIES_EYES; BLOCK_ENTITY; SHADOW_ENTITIES_CUTOUT. */
        EYES,
        /** ENTITIES_EYES_TRANS; BLOCK_ENTITY; SHADOW_BLOCK. */
        EMISSIVE
    }

    /**
     * Entity-format shaders by vanilla name; a render type routes by the shader it draws with, as
     * Iris overrides shader getters, so NeoForge's and mods' render types on these shaders follow.
     * Flat held and dropped items draw with item_entity_translucent_cull: drawn by the host they
     * wrote no gbuffer data, and deferred lighting erased them to a ghost. entity_alpha (the dying
     * dragon) stays on the host: Iris tests it against the vertex alpha, a threshold the pack
     * programs here do not take.
     */
    private static final Map<String, EntityShader> ENTITY_SHADERS = Map.ofEntries(
            Map.entry("rendertype_entity_solid", EntityShader.MODEL),
            Map.entry("rendertype_entity_cutout", EntityShader.MODEL),
            Map.entry("rendertype_entity_cutout_no_cull", EntityShader.MODEL),
            Map.entry("rendertype_entity_cutout_no_cull_z_offset", EntityShader.MODEL),
            Map.entry("rendertype_entity_decal", EntityShader.MODEL),
            Map.entry("rendertype_entity_smooth_cutout", EntityShader.MODEL),
            Map.entry("rendertype_armor_cutout_no_cull", EntityShader.MODEL),
            Map.entry("rendertype_energy_swirl", EntityShader.OVERLAY),
            Map.entry("rendertype_entity_shadow", EntityShader.OVERLAY),
            Map.entry("rendertype_entity_translucent", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_entity_translucent_cull", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_item_entity_translucent_cull", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_breeze_wind", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_entity_no_outline", EntityShader.TRANSLUCENT),
            Map.entry("rendertype_eyes", EntityShader.EYES),
            Map.entry("rendertype_entity_translucent_emissive", EntityShader.EMISSIVE));
    /** The chunk shaders Iris draws as MOVING_BLOCK when an entity or block entity draws with them. */
    private static final Set<String> MOVING_BLOCK_SHADERS = Set.of("rendertype_solid", "rendertype_cutout_mipped",
            "rendertype_cutout", "rendertype_translucent", "rendertype_translucent_moving_block",
            "rendertype_tripwire");
    /**
     * The glyph shaders Iris routes through TEXT and TEXT_BE, and through TEXT_INTENSITY and
     * TEXT_INTENSITY_BE for TrueType glyphs, whose one-channel page reads RRRR
     * (IntensityTextureViews): world, sign and see-through text.
     */
    private static final Set<String> TEXT_SHADERS = Set.of("rendertype_text", "rendertype_text_see_through",
            "position_color_tex_lightmap", "rendertype_text_intensity", "rendertype_text_intensity_see_through");

    private static PackEntityIdResolver resolver = PackEntityIdResolver.empty();
    /** The pack's lightning_bolt id as Iris's entityId uniform reads it. */
    private static int lightningBoltEntityId;
    /** True while LightningBoltRenderType, the bolt's render type, draws its batch. */
    private static boolean lightningBoltDraw;
    private static GraphicsPipeline entityPipeline;
    private static GraphicsPipeline translucentEntityPipeline;
    private static GraphicsPipeline spiderEyesPipeline;
    private static GraphicsPipeline blockPipeline;
    private static GraphicsPipeline damagedBlockPipeline;
    private static PackPipelines.PackHand handVariants;
    private static PackPipelines.PackHand handWaterVariants;
    private static GraphicsPipeline blockTranslucentPipeline;
    /** The translucent-entity and block-translucent programs built for Iris's GLYPH text layout. */
    private static GraphicsPipeline translucentEntityTextPipeline;
    private static GraphicsPipeline blockTranslucentTextPipeline;
    /** gbuffers_block on the block layout. */
    private static GraphicsPipeline movingBlockPipeline;
    private static GraphicsPipeline particlePipeline;
    private static GraphicsPipeline translucentParticlePipeline;
    /** The installed lanes of {@link HostLayoutLane}, keyed by their family. */
    private static final EnumMap<Family, GraphicsPipeline> hostLayoutPipelines = new EnumMap<>(Family.class);
    /** shadow_entities, drawing the shadow pass's entity-format casters, and on the block layout its moving blocks. */
    private static GraphicsPipeline shadowCasterPipeline;
    private static GraphicsPipeline shadowMovingBlockPipeline;
    /** shadow_block, drawing a block entity's models in the shadow pass. */
    private static GraphicsPipeline shadowBlockPipeline;
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
        lightningBoltEntityId = resolver.uniformId("minecraft:lightning_bolt");
        ChimeraMod.LOGGER.info("[chimera] family bridge: entity={}, entityTranslucent={}, spiderEyes={}, "
                        + "block={}, damagedBlock={}, hand={}, handWater={}, particle={}, particleTranslucent={}",
                status(entityPipeline), status(translucentEntityPipeline), status(spiderEyesPipeline),
                status(blockPipeline), status(damagedBlockPipeline), status(hand == null ? null : hand.primary()),
                status(handWater == null ? null : handWater.primary()), status(particlePipeline),
                status(translucentParticlePipeline));
    }

    /**
     * Installs gbuffers_block_translucent and the other layouts after {@link #install}: Iris draws
     * TEXT with gbuffers_entities_translucent, a block entity's BE_TRANSLUCENT and TEXT_BE with
     * gbuffers_block_translucent, and MOVING_BLOCK with gbuffers_block.
     */
    public static void installLayoutVariants(PackPipelines.PackEntity translucentEntityText,
                                             PackPipelines.PackEntity blockTranslucent,
                                             PackPipelines.PackEntity blockTranslucentText,
                                             PackPipelines.PackEntity movingBlock) {
        translucentEntityTextPipeline = pipeline(translucentEntityText);
        blockTranslucentPipeline = pipeline(blockTranslucent);
        blockTranslucentTextPipeline = pipeline(blockTranslucentText);
        movingBlockPipeline = pipeline(movingBlock);
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

    /**
     * Installs shadow_entities, its block layout and shadow_block after {@link #install}. Without
     * shadow_entities no entity casts a pack shadow; without shadow_block block entities cast
     * theirs with shadow_entities.
     */
    public static void installShadowCasters(PackPipelines.PackEntity casters,
                                            PackPipelines.PackEntity movingBlockCasters,
                                            PackPipelines.PackEntity blockCasters) {
        shadowCasterPipeline = pipeline(casters);
        shadowMovingBlockPipeline = pipeline(movingBlockCasters);
        shadowBlockPipeline = pipeline(blockCasters);
        ChimeraMod.LOGGER.info("[chimera] family bridge: shadow casters={}, moving blocks={}, block entities={}",
                status(shadowCasterPipeline), status(shadowMovingBlockPipeline), status(shadowBlockPipeline));
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
        blockTranslucentPipeline = null;
        translucentEntityTextPipeline = null;
        blockTranslucentTextPipeline = null;
        movingBlockPipeline = null;
        particlePipeline = null;
        translucentParticlePipeline = null;
        hostLayoutPipelines.clear();
        shadowCasterPipeline = null;
        shadowMovingBlockPipeline = null;
        shadowBlockPipeline = null;
        resolver = PackEntityIdResolver.empty();
        lightningBoltEntityId = 0;
        FamilyBufferSource.releaseAll();
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
        lightningBoltDraw = false;
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
        // RenderType.draw has no finally: a bolt draw that threw must not mark later lane draws.
        lightningBoltDraw = false;
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
     * ShadowRenderer.ACTIVE): every caster batch draws with its shadow family ({@link #shadowFamily}),
     * every host-layout draw with its shadow lane, and anything else stays out of the shadow map.
     */
    public static boolean beginShadowCasters() {
        lightningBoltDraw = false;
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

    /**
     * The context drawing now: BLOCK anywhere inside a block entity, as Iris's block-entity wrapper
     * wins over an entity's (a spawner's mob draws as the block entity's), else the innermost one.
     */
    private static Family context() {
        int depth = Math.min(contextDepth, contexts.length);
        for (int index = 0; index < depth; index++) {
            if (contexts[index] == Family.BLOCK) return Family.BLOCK;
        }
        return depth == 0 ? null : contexts[depth - 1];
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

    /** Opens or closes the lightning bolt's draw, Iris's LightningRenderStateShard window. */
    public static void setLightningBoltDraw(boolean drawing) {
        lightningBoltDraw = drawing;
    }

    /**
     * entityId as a host-layout lane reads it (Iris's fallback entityId uniform): the pack's
     * lightning_bolt id while a bolt draws, otherwise 0, the entity Iris has reset by the time a
     * host-layout batch draws.
     */
    public static int hostLayoutEntityId() {
        return enabled && lightningBoltDraw ? lightningBoltEntityId : 0;
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
        Family family;
        if (shadowCasterPass) {
            family = shadowFamily(shaderOf(type), format, context());
        } else if (ChimeraHandRenderer.active()) {
            family = ChimeraHandRenderer.translucentPhase() ? Family.HAND_WATER : Family.HAND;
        } else {
            Family context = worldWindow ? context() : null;
            family = context == null ? null : worldFamily(shaderOf(type), format, context);
        }
        if (family != null && pipelineFor(family, widenedFormat(family, format)) == null) {
            family = fallback(family, format);
        }
        return family != null && pipelineFor(family, widenedFormat(family, format)) != null ? family : null;
    }

    /**
     * The lane of the next program in Iris's chain, where a lane's own program is not installed:
     * shadow_block's caster lane, and gbuffers_block for a block entity's translucent models.
     */
    private static Family fallback(Family family, VertexFormat format) {
        if (format != DefaultVertexFormat.NEW_ENTITY) return null;
        return switch (family) {
            case SHADOW_BLOCK -> Family.SHADOW_ENTITIES;
            case BLOCK_TRANSLUCENT -> Family.BLOCK;
            default -> null;
        };
    }

    /** The shader a composite render type draws with, which Iris keys its overrides on; null for none. */
    private static String shaderOf(RenderType type) {
        if (!(type instanceof ChimeraCompositeRenderTypeAccessor composite)) return null;
        var shard = ((ChimeraCompositeStateAccessor) (Object) composite.chimera$state()).chimera$shaderState();
        ShaderInstance shader = ((ChimeraShaderStateShardAccessor) shard).chimera$shader().map(Supplier::get)
                .orElse(null);
        if (shader == null) return null;
        // VulkanMod Reforged loads breeze wind's shader as a second instance named rendertype_energy_swirl.
        return shader == GameRenderer.getRendertypeBreezeWindShader() ? "rendertype_breeze_wind" : shader.getName();
    }

    /**
     * The family Iris 1.21.1 draws a world render type with, by its shader and format, inside an
     * entity ({@code context} ENTITY) or a block entity (BLOCK); null when Iris keeps it on another
     * program. Text is TEXT (gbuffers_entities_translucent) in an entity and TEXT_BE
     * (gbuffers_block_translucent) in a block entity.
     */
    public static Family worldFamily(String shader, VertexFormat format, Family context) {
        if (shader == null) return null;
        boolean blockEntity = context == Family.BLOCK;
        if (format == DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP) {
            if (!TEXT_SHADERS.contains(shader)) return null;
            return blockEntity ? Family.BLOCK_TRANSLUCENT : Family.ENTITY_TRANSLUCENT;
        }
        if (format == DefaultVertexFormat.BLOCK) {
            return MOVING_BLOCK_SHADERS.contains(shader) ? Family.MOVING_BLOCK : null;
        }
        EntityShader key = format == DefaultVertexFormat.NEW_ENTITY ? ENTITY_SHADERS.get(shader) : null;
        if (key == null) return null;
        return switch (key) {
            case MODEL, OVERLAY -> blockEntity ? Family.BLOCK : Family.ENTITY;
            case TRANSLUCENT -> blockEntity ? Family.BLOCK_TRANSLUCENT : Family.ENTITY_TRANSLUCENT;
            case EYES, EMISSIVE -> blockEntity ? Family.BLOCK : Family.SPIDER_EYES;
        };
    }

    /**
     * The family Iris 1.21.1 draws a shadow caster with, by its shader and format: a block entity's
     * models SHADOW_BLOCK, falling blocks and moving pistons the shadow program on the block layout,
     * and every other entity-format caster SHADOW_ENTITIES_CUTOUT. Null leaves it out of the shadow
     * map.
     */
    public static Family shadowFamily(String shader, VertexFormat format, Family context) {
        if (format == DefaultVertexFormat.BLOCK) {
            return context != null && MOVING_BLOCK_SHADERS.contains(shader) ? Family.SHADOW_ENTITIES : null;
        }
        if (format != DefaultVertexFormat.NEW_ENTITY) return null;
        EntityShader key = shader == null ? null : ENTITY_SHADERS.get(shader);
        boolean blockModel = context == Family.BLOCK && key != null
                && key != EntityShader.OVERLAY && key != EntityShader.EYES;
        return blockModel ? Family.SHADOW_BLOCK : Family.SHADOW_ENTITIES;
    }

    /** The vertex format a family lane writes for a host format: Iris's attributes appended. */
    static VertexFormat widenedFormat(Family family, VertexFormat format) {
        return switch (family) {
            case HAND, HAND_WATER, ENTITY, ENTITY_TRANSLUCENT, SPIDER_EYES, BLOCK, BLOCK_TRANSLUCENT, MOVING_BLOCK,
                 SHADOW_ENTITIES, SHADOW_BLOCK -> Objects.requireNonNullElse(
                         ChimeraVertexFormats.extendedLayout(format), format);
            default -> format;
        };
    }

    /**
     * The host-layout lane that draws a host mesh now, by its shader, or null when the host draws
     * it: a {@link HostLayoutLane} of the current pass, a particle, or the crumbling overlay.
     */
    public static Family hostLaneFor(String shader, VertexFormat format) {
        if (!enabled || drawActive || shader == null) return null;
        HostLayoutLane.Pass pass = currentPass();
        if (pass == null) return null;
        HostLayoutLane lane = HostLayoutLane.forDraw(shader, format, pass);
        if (lane != null) return hostLayoutPipelines.containsKey(lane.family()) ? lane.family() : null;
        return pass == HostLayoutLane.Pass.WORLD ? worldHostLane(shader, format) : null;
    }

    /** The pass a host draw belongs to now, or null outside every lane window. */
    private static HostLayoutLane.Pass currentPass() {
        if (shadowCasterPass) return HostLayoutLane.Pass.SHADOW;
        if (ChimeraHandRenderer.active()) return HostLayoutLane.Pass.HAND;
        if (!worldWindow) return null;
        return weatherWindow ? HostLayoutLane.Pass.WEATHER : HostLayoutLane.Pass.WORLD;
    }

    /** A world draw's particle or crumbling lane: the families outside the host-layout table. */
    private static Family worldHostLane(String shader, VertexFormat format) {
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
        HostLayoutLane lane = HostLayoutLane.of(family);
        return beginDraw(family, format, shader, lane != null ? lane.phase() : phase(family));
    }

    /** Opens a draw as {@link #beginDraw(Family, VertexFormat, String)} does, in {@code phase}. */
    public static boolean beginDraw(Family family, VertexFormat format, String shader, PackRenderingPhase phase) {
        if (!enabled || drawActive) return false;
        GraphicsPipeline selected = pipelineFor(family, format);
        if (selected == null) return false;
        HostLayoutLane lane = HostLayoutLane.of(family);
        activeFamily = family;
        activePipeline = selected;
        activeAlphaReference = lane != null ? lane.alphaReference()
                : HostAlphaTest.reference(shader, family, shadowCasterPass);
        activePhase = phase;
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
            case BLOCK_TRANSLUCENT -> format == ChimeraVertexFormats.EXTENDED_GLYPH
                    ? blockTranslucentTextPipeline : blockTranslucentPipeline;
            case MOVING_BLOCK -> movingBlockPipeline;
            case DAMAGED_BLOCK -> damagedBlockPipeline;
            case HAND, HAND_WATER -> handPipeline(family, format);
            case PARTICLE -> particlePipeline;
            case PARTICLE_TRANSLUCENT -> translucentParticlePipeline;
            case SHADOW_ENTITIES, SHADOW_BLOCK -> shadowCasterPipeline(family, format);
            default -> hostLayoutPipelines.get(family);
        };
    }

    /** A shadow caster family's pipeline for {@code format}; null outside the caster window. */
    private static GraphicsPipeline shadowCasterPipeline(Family family, VertexFormat format) {
        if (!shadowCasterPass) return null;
        if (family == Family.SHADOW_BLOCK) return shadowBlockPipeline;
        return format == ChimeraVertexFormats.EXTENDED_BLOCK ? shadowMovingBlockPipeline : shadowCasterPipeline;
    }

    private static GraphicsPipeline handPipeline(Family family, VertexFormat format) {
        var variants = family == Family.HAND ? handVariants : handWaterVariants;
        var variant = variants == null ? null : variants.forFormat(format);
        return variant == null ? null : variant.pipeline();
    }

    /**
     * The Iris rendering phase a family batch written now draws in: BLOCK_ENTITIES for whatever a
     * block entity draws, as Iris wraps a block entity's render types in BlockEntityRenderStateShard
     * in either pass, and otherwise the family's own.
     */
    public static PackRenderingPhase writePhase(Family family) {
        return context() == Family.BLOCK ? PackRenderingPhase.BLOCK_ENTITIES : phase(family);
    }

    /** The Iris rendering phase a family outside the host-layout lanes draws in. */
    private static PackRenderingPhase phase(Family family) {
        return switch (family) {
            // Iris holds the ENTITIES phase for entities and their shadow casters.
            case ENTITY, ENTITY_TRANSLUCENT, SPIDER_EYES, MOVING_BLOCK, SHADOW_ENTITIES -> PackRenderingPhase.ENTITIES;
            case BLOCK, BLOCK_TRANSLUCENT, SHADOW_BLOCK -> PackRenderingPhase.BLOCK_ENTITIES;
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
