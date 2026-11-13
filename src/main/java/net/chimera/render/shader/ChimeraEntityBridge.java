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
import net.chimera.shaderpack.PackRenderingPhase;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Render-thread state of the pack geometry families: which pack program draws which host draw.
 *
 * <p>Two kinds of lane exist. A family lane (entities, block entities, their text, moving blocks,
 * the crumbling overlay, beacon beams, the hand and shadow casters) widens the host vertex format with Iris's
 * attributes, so its geometry is batched apart from the host's in a {@link FamilyRenderType} the
 * moment it is written: the lane is chosen from the render type and the context drawing it
 * (EntityRenderDispatcher, BlockEntityRenderDispatcher, the hand, the shadow casters). The crumbling
 * overlay and beacon beams take their lane from the render type alone, wherever they are written
 * (worldTypeFamily). A host-layout
 * lane (particles and the {@link HostLayoutLane}s) keeps the host mesh and is chosen when it draws,
 * by shader name.</p>
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
        /**
         * Beacon and end gateway beams, on the block layout widened as Iris widens it to TERRAIN,
         * drawn with gbuffers_beaconbeam (Iris BEACON).
         */
        BEACON_BEAM,
        LEASH,
        /** Text and name-tag backgrounds (Iris TEXT_BG), drawn with gbuffers_entities_translucent. */
        TEXT_BG,
        /** World position_tex draws, the world border among them (Iris TEXTURED), drawn with gbuffers_textured. */
        TEXTURED,
        /** World position_tex_color draws (Iris TEXTURED_COLOR), with gbuffers_textured's chain. */
        TEXTURED_COLOR,
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
        SHADOW_TEXT,
        SHADOW_TEXT_BG,
        SHADOW_BASIC,
        SHADOW_TEX,
        SHADOW_TEX_COLOR
    }

    /** The session's lane pipelines and entity ids, installed and dropped whole. */
    private static InstalledLanes lanes = InstalledLanes.NONE;
    /** The pack's lightning_bolt id as Iris's entityId uniform reads it. */
    private static int lightningBoltEntityId;
    /** True while LightningBoltRenderType, the bolt's render type, draws its batch. */
    private static boolean lightningBoltDraw;
    private static boolean enabled;

    /** True from LevelRenderer.renderLevel's head to its return while the pack renders the world. */
    private static boolean worldWindow;
    /** True while the shadow pass draws its casters (Iris ShadowRenderer.ACTIVE). */
    private static boolean shadowCasterPass;
    private static boolean particleWindow;
    private static boolean particleTranslucent;
    private static boolean weatherWindow;
    /** The Iris rendering phase of the held lane batch drawing now (HeldLaneRenderType), or null. */
    private static PackRenderingPhase heldBatchPhase;

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

    /**
     * Installs a session's lanes whole, in place of any installed before. Iris draws TEXT with
     * gbuffers_entities_translucent, a block entity's BE_TRANSLUCENT and TEXT_BE with
     * gbuffers_block_translucent, and MOVING_BLOCK with gbuffers_block. Without shadow_entities no
     * entity casts a pack shadow; without shadow_block block entities cast theirs with
     * shadow_entities. A host-layout lane without a pipeline keeps its host draw.
     */
    public static void install(InstalledLanes next) {
        disable();
        lanes = next == null ? InstalledLanes.NONE : next;
        lightningBoltEntityId = lanes.entityIds().uniformId("minecraft:lightning_bolt");
        ChimeraMod.LOGGER.debug("[chimera] family bridge: entity={}, entityTranslucent={}, spiderEyes={}, "
                        + "block={}, damagedBlock={}, hand={}, handWater={}, particle={}, particleTranslucent={}",
                status(lanes.entity()), status(lanes.translucentEntity()), status(lanes.spiderEyes()),
                status(lanes.block()), status(lanes.damagedBlock()),
                status(lanes.hand() == null ? null : lanes.hand().primary()),
                status(lanes.handWater() == null ? null : lanes.handWater().primary()), status(lanes.particle()),
                status(lanes.translucentParticle()));
        if (!lanes.hostLayout().isEmpty()) {
            ChimeraMod.LOGGER.debug("[chimera] family bridge: host-layout lanes={}",
                    lanes.hostLayout().keySet().stream().map(family -> family.name().toLowerCase()).toList());
        }
        ChimeraMod.LOGGER.debug("[chimera] family bridge: shadow casters={}, moving blocks={}, block entities={}",
                status(lanes.shadowEntities()), status(lanes.shadowMovingBlock()), status(lanes.shadowBlock()));
    }

    /**
     * Switches the lanes on or off. Off, the level sources drop what they hold: a pack that falls
     * back mid-frame left batches held for a boundary its lanes no longer reach (audit LD1).
     */
    public static void setEnabled(boolean value) {
        enabled = value && isInstalled();
        if (!enabled) {
            resetFrameState();
            FamilyBufferSource.discardLevelBatches();
        }
    }

    /** Uninstalls every lane and frees the family batches built for them. */
    public static void disable() {
        enabled = false;
        resetFrameState();
        lanes = InstalledLanes.NONE;
        lightningBoltEntityId = 0;
        FamilyBufferSource.releaseAll();
        resetDrawTrace();
    }

    public static boolean isInstalled() {
        return lanes.any();
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
        // RenderType.draw and ItemRenderer.render have no finally: a draw, bolt draw or item that
        // threw must not carry into the frame.
        closeOpenDraw();
        RenderedItemIds.unwind();
        worldWindow = enabled;
        contextDepth = 0;
        lightningBoltDraw = false;
    }

    /** Ends a family draw a failed RenderType.draw left open, restoring the textures it saved. */
    static void closeOpenDraw() {
        if (drawActive) endDraw();
    }

    public static void endWorldWindow() {
        worldWindow = false;
        contextDepth = 0;
        currentEntityId = 0;
        currentBlockEntityId = 0;
    }

    /**
     * Iris's ImmediateState.isRenderingLevel: from LevelRenderer.renderLevel's head to its return.
     * Both hand halves fall inside it: Iris draws the translucent one at the return shifted before
     * it (MixinLevelRenderer.iris$endLevelRender), ahead of the plain-RETURN injection that clears
     * the flag.
     */
    public static boolean renderingLevel() {
        return worldWindow;
    }

    /** True while RenderBuffers' sources route family render types (ChimeraRenderBuffersMixin). */
    public static boolean routesWorldBuffers() {
        return enabled && worldWindow && !drawActive;
    }

    /**
     * Opens the shadow pass's caster window around its entity and block-entity drawing (Iris
     * ShadowRenderer.ACTIVE): every caster batch draws with its shadow family (IrisShaderKeys.shadowFamily),
     * every host-layout draw with its shadow lane, and anything else stays out of the shadow map.
     */
    public static boolean beginShadowCasters() {
        lightningBoltDraw = false;
        shadowCasterPass = enabled && !drawActive && lanes.shadowEntities() != null;
        return shadowCasterPass;
    }

    public static void endShadowCasters() {
        closeOpenDraw();
        RenderedItemIds.unwind();
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

    /**
     * Picks the particle program for a ParticleEngine.render call that draws the sheets
     * {@code sheets} accepts, as Iris's NeoForge MixinParticleEngine sets one shader per call.
     */
    public static void beginParticleCall(Predicate<ParticleRenderType> sheets) {
        particleTranslucent = translucentParticleCall(sheets);
    }

    /**
     * Whether a particle call draws every sheet with gbuffers_particles_translucent: only when it
     * leaves the opaque sheet out, as NeoForge's late call does under MIXED. Under BEFORE and AFTER
     * every sheet draws with gbuffers_particles, each with its own blend. A sheet's own
     * isTranslucent() does not decide it: NeoForge makes that true for the terrain and custom sheets.
     */
    static boolean translucentParticleCall(Predicate<ParticleRenderType> sheets) {
        return !sheets.test(ParticleRenderType.PARTICLE_SHEET_OPAQUE);
    }

    /**
     * The Iris rendering phase a world host-layout lane's batch written now draws in. Iris answers
     * renderStage for such a draw from the wrapper around the render type it was asked for in
     * (OuterWrappedRenderType): BLOCK_ENTITIES inside a block entity's render, ENTITIES inside an
     * entity's, so a hitbox or a fishing line reports ENTITIES and a structure block's box
     * BLOCK_ENTITIES. Outside every wrapper the batch draws in NONE, the phase Iris holds between
     * its phase windows.
     *
     * <p>The block outline reports NONE too. Iris's source wraps it in its outline state
     * (MixinLevelRenderer.iris$beginBlockOutline), but the Iris the owner compares against
     * (1.8.14-beta.1 on Fabric with Sodium) reads renderStage 0 at the outline's draw in Glimmer's
     * and Mellow's captures. Reporting OUTLINE made Glimmer's line transparent and Mellow's opaque
     * black, where Iris shows vanilla's translucent outline.</p>
     */
    public static PackRenderingPhase hostLaneWritePhase() {
        return wrapperPhase(context());
    }

    /** The phase of the wrapper Iris asks for a render type in, by the context rendering. */
    static PackRenderingPhase wrapperPhase(Family context) {
        if (context == Family.BLOCK) return PackRenderingPhase.BLOCK_ENTITIES;
        return context == Family.ENTITY ? PackRenderingPhase.ENTITIES : PackRenderingPhase.NONE;
    }

    /** While a held lane batch draws, its lane draws in the phase it was written in. */
    static void beginHeldBatch(PackRenderingPhase phase) {
        heldBatchPhase = phase;
    }

    static void endHeldBatch() {
        heldBatchPhase = null;
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
        push(Family.ENTITY, lanes.entityIds().resolveName(name), 0);
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
        return lanes.entityIds().resolveName("minecraft:entity_flame");
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
            family = IrisShaderKeys.shadowFamily(shaderOf(type), format, context());
        } else if (ChimeraHandRenderer.active()) {
            family = ChimeraHandRenderer.translucentPhase() ? Family.HAND_WATER : Family.HAND;
        } else {
            family = worldWindow ? worldTypeFamily(shaderOf(type), format) : null;
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

    /**
     * The shader a composite render type draws with, which Iris keys its overrides on, the bolt's
     * through the lightning type it wraps; null for none.
     */
    private static String shaderOf(RenderType type) {
        if (!(LightningBoltRenderType.unwrap(type) instanceof ChimeraCompositeRenderTypeAccessor composite)) {
            return null;
        }
        var shard = ((ChimeraCompositeStateAccessor) (Object) composite.chimera$state()).chimera$shaderState();
        ShaderInstance shader = ((ChimeraShaderStateShardAccessor) shard).chimera$shader().map(Supplier::get)
                .orElse(null);
        if (shader == null) return null;
        // VulkanMod Reforged loads breeze wind's shader as a second instance named rendertype_energy_swirl.
        return shader == GameRenderer.getRendertypeBreezeWindShader() ? "rendertype_breeze_wind" : shader.getName();
    }

    /**
     * The family a render type written in the world window batches with: the crumbling overlay and
     * beacon beams wherever they are written, as Iris widens every BLOCK buffer to TERRAIN, and
     * otherwise the entity or block entity drawing it (IrisShaderKeys.worldFamily).
     */
    public static Family worldTypeFamily(String shader, VertexFormat format) {
        if (format == DefaultVertexFormat.BLOCK && IrisShaderKeys.CRUMBLING_SHADER.equals(shader)) {
            return Family.DAMAGED_BLOCK;
        }
        if (format == DefaultVertexFormat.BLOCK && IrisShaderKeys.BEACON_BEAM_SHADER.equals(shader)) {
            return Family.BEACON_BEAM;
        }
        Family context = context();
        return context == null ? null : IrisShaderKeys.worldFamily(shader, format, context);
    }

    /** The vertex format a family lane writes for a host format: Iris's attributes appended. */
    static VertexFormat widenedFormat(Family family, VertexFormat format) {
        return switch (family) {
            case HAND, HAND_WATER, ENTITY, ENTITY_TRANSLUCENT, SPIDER_EYES, BLOCK, BLOCK_TRANSLUCENT, MOVING_BLOCK,
                 DAMAGED_BLOCK, BEACON_BEAM, SHADOW_ENTITIES, SHADOW_BLOCK -> Objects.requireNonNullElse(
                         ChimeraVertexFormats.extendedLayout(format), format);
            default -> format;
        };
    }

    /**
     * The host-layout lane that draws a host mesh now, by its shader, or null when the host draws
     * it: a {@link HostLayoutLane} of the current pass, or a particle.
     */
    public static Family hostLaneFor(String shader, VertexFormat format) {
        if (!enabled || drawActive || shader == null) return null;
        HostLayoutLane.Pass pass = currentPass();
        if (pass == null) return null;
        HostLayoutLane lane = HostLayoutLane.forDraw(shader, format, pass);
        if (lane != null) return lanes.hostLayout().containsKey(lane.family()) ? lane.family() : null;
        return pass == HostLayoutLane.Pass.WORLD ? worldHostLane(shader, format) : null;
    }

    /**
     * Whether a world host-layout lane draws {@code type}: the level's buffer source holds such a
     * batch with the entities' (FamilyBufferSource), as Iris's batched source holds every batch.
     */
    public static boolean worldLaneDraws(RenderType type) {
        return currentPass() == HostLayoutLane.Pass.WORLD && hostLaneFor(shaderOf(type), type.format()) != null;
    }

    /**
     * The pass a host draw belongs to now, or null outside every lane window. The sky has no lane:
     * Iris draws its host shaders with the sky keys, which ChimeraSkyBridge serves.
     */
    private static HostLayoutLane.Pass currentPass() {
        if (shadowCasterPass) return HostLayoutLane.Pass.SHADOW;
        if (ChimeraHandRenderer.active()) return HostLayoutLane.Pass.HAND;
        if (!worldWindow || ChimeraSkyBridge.inSkyWindow()) return null;
        return weatherWindow ? HostLayoutLane.Pass.WEATHER : HostLayoutLane.Pass.WORLD;
    }

    /**
     * A world draw's particle lane, the family outside the host-layout table drawn on its host mesh.
     * The crumbling overlay is a family batch on the widened block layout (familyFor).
     */
    private static Family worldHostLane(String shader, VertexFormat format) {
        if (!particleWindow || !shader.equals("particle") || format != DefaultVertexFormat.PARTICLE) return null;
        // The call's program draws every sheet in it (beginParticleCall), each with its host blend.
        if (particleTranslucent) return lanes.translucentParticle() != null ? Family.PARTICLE_TRANSLUCENT : null;
        return lanes.particle() != null ? Family.PARTICLE : null;
    }

    /**
     * True for a shadow-pass draw no shadow lane serves. Iris draws every shadow-pass draw with a
     * shadow program; this one stays out of the shadow map rather than writing colour and depth
     * with its host shader.
     */
    public static boolean dropsHostDraw(String shader) {
        if (!isShadowCasterPass() || drawActive) return false;
        noteShadowDropped(shader);
        return true;
    }

    // ------------------------------------------------------------------
    // Draws
    // ------------------------------------------------------------------

    /**
     * Opens a draw of {@code family} on {@code format} with the host shader {@code shader}; false
     * when the lane has no pipeline for that format. A host-layout lane draws a held batch in the
     * phase it was written in, and any other draw in its own.
     */
    public static boolean beginDraw(Family family, VertexFormat format, String shader) {
        HostLayoutLane lane = HostLayoutLane.of(family);
        return beginDraw(family, format, shader, lane == null ? phase(family) : lanePhase(lane));
    }

    /** The phase a host-layout lane draws in now: the held batch's while one draws, else the lane's own. */
    static PackRenderingPhase lanePhase(HostLayoutLane lane) {
        return heldBatchPhase != null ? heldBatchPhase : lane.phase();
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
                : IrisShaderKeys.reference(shader, family, shadowCasterPass);
        activePhase = phase;
        for (int index = 0; index < VTextureSelector.SIZE; index++) {
            previousTextures[index] = VTextureSelector.getBoundTexture(index);
        }
        drawActive = true;
        noteDraw(family, shader, format);
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

    /** Iris ExtendedShader binds a white lightmap for a key with no light input (whiteLightmap(Family)). */
    public static boolean whiteLightmap() {
        return activeFamily != null && whiteLightmap(activeFamily);
    }

    /**
     * Whether Iris samples a white lightmap for the family's key: a host-layout lane's format
     * without UV2, or a FULLBRIGHT key, which are ENTITIES_EYES and ENTITIES_EYES_TRANS (the
     * spider-eyes family outside block entities), BEACON and CRUMBLING.
     */
    public static boolean whiteLightmap(Family family) {
        HostLayoutLane lane = HostLayoutLane.of(family);
        if (lane != null) return lane.whiteLightmap();
        return family == Family.SPIDER_EYES || family == Family.BEACON_BEAM || family == Family.DAMAGED_BLOCK;
    }

    /** The pack pipeline a lane draws {@code format} with, or null when it has none installed. */
    public static GraphicsPipeline lanePipeline(Family family, VertexFormat format) {
        return pipelineFor(family, format);
    }

    private static GraphicsPipeline pipelineFor(Family family, VertexFormat format) {
        return switch (family) {
            case ENTITY -> lanes.entity();
            case ENTITY_TRANSLUCENT -> format == ChimeraVertexFormats.EXTENDED_GLYPH
                    ? lanes.translucentEntityText() : lanes.translucentEntity();
            case SPIDER_EYES -> lanes.spiderEyes();
            case BLOCK -> lanes.block();
            case BLOCK_TRANSLUCENT -> format == ChimeraVertexFormats.EXTENDED_GLYPH
                    ? lanes.blockTranslucentText() : lanes.blockTranslucent();
            case MOVING_BLOCK -> lanes.movingBlock();
            case DAMAGED_BLOCK, BEACON_BEAM -> widenedBlockPipeline(family, format);
            case HAND, HAND_WATER -> handPipeline(family, format);
            case PARTICLE -> lanes.particle();
            case PARTICLE_TRANSLUCENT -> lanes.translucentParticle();
            case SHADOW_ENTITIES, SHADOW_BLOCK -> shadowCasterPipeline(family, format);
            default -> lanes.hostLayout().get(family);
        };
    }

    /** The crumbling overlay's or a beacon beam's pipeline, which draw only on the widened block layout. */
    private static GraphicsPipeline widenedBlockPipeline(Family family, VertexFormat format) {
        if (format != ChimeraVertexFormats.EXTENDED_BLOCK) return null;
        return family == Family.DAMAGED_BLOCK ? lanes.damagedBlock() : lanes.beaconBeam();
    }

    /** A shadow caster family's pipeline for {@code format}; null outside the caster window. */
    private static GraphicsPipeline shadowCasterPipeline(Family family, VertexFormat format) {
        if (!shadowCasterPass) return null;
        if (family == Family.SHADOW_BLOCK) return lanes.shadowBlock();
        return format == ChimeraVertexFormats.EXTENDED_BLOCK ? lanes.shadowMovingBlock() : lanes.shadowEntities();
    }

    private static GraphicsPipeline handPipeline(Family family, VertexFormat format) {
        var variants = family == Family.HAND ? lanes.hand() : lanes.handWater();
        var variant = variants == null ? null : variants.forFormat(format);
        return variant == null ? null : variant.pipeline();
    }

    /**
     * The Iris rendering phase a family batch written now draws in: BLOCK_ENTITIES for whatever a
     * block entity draws, as Iris wraps a block entity's render types in BlockEntityRenderStateShard
     * in either pass, and otherwise the family's own. A block entity's crumbling overlay is written
     * from the crumbling source, outside that wrapper, and draws in NONE: vanilla ends the crumbling
     * batch after every block entity's wrapper has closed, and Iris never sets DESTROY.
     */
    public static PackRenderingPhase writePhase(Family family) {
        return family != Family.DAMAGED_BLOCK && context() == Family.BLOCK
                ? PackRenderingPhase.BLOCK_ENTITIES : phase(family);
    }

    /** The Iris rendering phase a family outside the host-layout lanes draws in. */
    private static PackRenderingPhase phase(Family family) {
        return switch (family) {
            // Iris holds the ENTITIES phase for entities and their shadow casters.
            case ENTITY, ENTITY_TRANSLUCENT, SPIDER_EYES, MOVING_BLOCK, SHADOW_ENTITIES -> PackRenderingPhase.ENTITIES;
            case BLOCK, BLOCK_TRANSLUCENT, SHADOW_BLOCK -> PackRenderingPhase.BLOCK_ENTITIES;
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

    private static String status(Object value) {
        return value == null ? "fallback" : "installed";
    }


    // One-time diagnostics for the family lanes: the first draw of each lane, a refused family batch
    // and each host shader the shadow pass leaves out are logged once per pack session, so a log
    // shows which path each lane took.
    private static final EnumSet<Family> drawnLanes = EnumSet.noneOf(Family.class);
    private static final EnumSet<Family> refusedLanes = EnumSet.noneOf(Family.class);
    private static final Set<String> droppedShaders = new HashSet<>();

    /** Forgets what was logged, so a newly installed pack logs each lane's path again. */
    private static void resetDrawTrace() {
        drawnLanes.clear();
        refusedLanes.clear();
        droppedShaders.clear();
    }

    private static void noteDraw(Family family, String shader, VertexFormat format) {
        if (drawnLanes.add(family)) {
            ChimeraMod.LOGGER.debug("[chimera] family bridge: lane={} drawing shader={} stride={}B",
                    family.name().toLowerCase(), shader, format.getVertexSize());
        }
    }

    /** A family batch whose lane had no pipeline when it drew; the pipeline seam skips it. */
    static void noteDrawRefused(Family family, RenderType host) {
        if (refusedLanes.add(family)) {
            ChimeraMod.LOGGER.warn("[chimera] family bridge: lane={} refused its batch type={}",
                    family.name().toLowerCase(), host.name);
        }
    }

    /** A shadow-pass draw no shadow lane serves, left out of the shadow map as Iris draws none like it. */
    private static void noteShadowDropped(String shader) {
        if (droppedShaders.add(String.valueOf(shader))) {
            ChimeraMod.LOGGER.debug("[chimera] family bridge: shadow pass left out shader={}", shader);
        }
    }
}
