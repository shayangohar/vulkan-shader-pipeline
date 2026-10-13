package net.chimera.render;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.shaderpack.PackPipelines;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The pipelines one pack session installed, by family, and the routes in use. A null family has
 * no pack pipeline and keeps its host draw.
 */
final class PackPipelineSet {
    /** Pack programs swapped onto the resolve/present seams; null = identity. */
    GraphicsPipeline compositePipeline;
    GraphicsPipeline finalPipeline;
    PackPipelines.PackTerrain terrainRoute;
    PackPipelines.PackTerrain waterRoute;
    int[] compositeSlots;
    int[] finalSlots;
    List<String> compositeSamplerNames = List.of();
    List<String> finalSamplerNames = List.of();

    /** All successfully built post programs, sorted by deterministic pass order. */
    final List<PackPipelines.PackPost> postStages = new ArrayList<>();

    /** The final pass is retained separately because it runs before hand/GUI composition. */
    PackPipelines.PackPost finalPost;

    /** Pack geometry program (gbuffers_terrain) on the terrain path; null = chimera's terrain pipeline. */
    GraphicsPipeline terrainPipeline;
    int[] terrainSlots;

    /** Pack water program on the host translucent terrain path; null = host/chimera fallback. */
    GraphicsPipeline waterPipeline;
    int[] waterSlots;

    /** Pack shadow program on the shadow terrain path; null means fixed identity shadow. */
    GraphicsPipeline shadowPipeline;
    int[] shadowSlots;

    /** shadow_entities on the shadow pass's entity lane; null leaves entities out of the shadow map. */
    PackPipelines.PackEntity shadowEntities;

    /** Pack world-entity program on the guarded delayed model batch. */
    PackPipelines.PackEntity entity;

    /** Pack translucent world-entity program on the guarded delayed model batch. */
    PackPipelines.PackEntity translucentEntity;

    /** Pack spider-eyes program (Iris EYES and ENTITY_TRANSLUCENT_EMISSIVE) on the delayed model batch. */
    PackPipelines.PackEntity spiderEyes;

    /** Pack block-entity program on the guarded delayed model batch. */
    PackPipelines.PackEntity block;

    /** Pack damaged-block program on the guarded block draw lane. */
    PackPipelines.PackEntity damagedBlock;

    /** The translucent-entity and block programs on Iris's GLYPH text layout (TEXT, TEXT_BE). */
    PackPipelines.PackEntity translucentEntityText;
    PackPipelines.PackEntity blockText;

    /** Pack first-person hand program on the guarded hand draw window. */
    PackPipelines.PackHand hand;

    /** Pack first-person water program on the guarded hand draw window. */
    PackPipelines.PackHand handWater;

    /** Pack particle program on the host particle draw window. */
    PackPipelines.PackParticle particle;

    /** Pack translucent particle program on the host translucent particle window. */
    PackPipelines.PackParticle translucentParticle;

    /** Pack programs of the installed host-layout lanes (weather, lines, glint, lightning, ...). */
    final EnumMap<ChimeraEntityBridge.Family, PackPipelines.PackParticle> hostLayout =
            new EnumMap<>(ChimeraEntityBridge.Family.class);

    PackPipelines.PackSkyFamily skyBasic;
    PackPipelines.PackSkyFamily skyTextured;
    PackPipelines.PackSky cloud;
    boolean cloudsDrawNothing;

    /**
     * Destroys every installed pipeline. A post program installed as composite or final is one
     * pipeline, destroyed once.
     */
    void destroy() {
        Set<GraphicsPipeline> postPipelines = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PackPipelines.PackPost post : postStages) {
            postPipelines.add(post.pipeline());
        }
        if (compositePipeline != null) postPipelines.add(compositePipeline);
        if (finalPipeline != null) postPipelines.add(finalPipeline);
        postPipelines.forEach(GraphicsPipeline::cleanUp);
        if (terrainPipeline != null) terrainPipeline.cleanUp();
        if (waterPipeline != null) waterPipeline.cleanUp();
        terrainRoute = null;
        waterRoute = null;
        if (shadowPipeline != null) shadowPipeline.cleanUp();
        Stream.of(shadowEntities, entity, translucentEntity, spiderEyes, block, damagedBlock,
                        translucentEntityText, blockText)
                .filter(Objects::nonNull).forEach(value -> value.pipeline().cleanUp());
        if (hand != null) hand.cleanUp();
        if (handWater != null) handWater.cleanUp();
        if (particle != null) particle.pipeline().cleanUp();
        if (translucentParticle != null) translucentParticle.pipeline().cleanUp();
        hostLayout.values().forEach(lane -> lane.pipeline().cleanUp());
        if (skyBasic != null) skyBasic.cleanUp();
        if (skyTextured != null) skyTextured.cleanUp();
        if (cloud != null) cloud.pipeline().cleanUp();
    }

    /** Forgets every pipeline, leaving each family to its host draw. */
    void clear() {
        postStages.clear();
        compositePipeline = null;
        compositeSlots = null;
        compositeSamplerNames = List.of();
        finalPipeline = null;
        finalSlots = null;
        finalSamplerNames = List.of();
        finalPost = null;
        terrainPipeline = null;
        terrainSlots = null;
        terrainRoute = null;
        waterPipeline = null;
        waterSlots = null;
        waterRoute = null;
        shadowPipeline = null;
        shadowSlots = null;
        shadowEntities = null;
        entity = null;
        translucentEntity = null;
        spiderEyes = null;
        block = null;
        damagedBlock = null;
        translucentEntityText = null;
        blockText = null;
        hand = null;
        handWater = null;
        particle = null;
        translucentParticle = null;
        hostLayout.clear();
        skyBasic = null;
        skyTextured = null;
        cloud = null;
        cloudsDrawNothing = false;
    }

    /** Returns the installed pack pipeline for a family window request. */
    public PackPipelines.PackEntity familyPipeline(
            ChimeraEntityBridge.Family family) {
        return switch (family) {
            case ENTITY -> this.entity;
            case ENTITY_TRANSLUCENT -> this.translucentEntity;
            case SPIDER_EYES -> this.spiderEyes;
            case BLOCK -> this.block;
            case DAMAGED_BLOCK -> this.damagedBlock;
            case HAND -> this.hand == null ? null : this.hand.primary();
            case HAND_WATER -> this.handWater == null ? null : this.handWater.primary();
            // The shadow pass owns the shadow map's attachments; casters open no window.
            case SHADOW_ENTITIES -> this.shadowEntities;
            default -> null;
        };
    }

    /** The selected host-particle or host-layout lane retains its own authored output contract. */
    public PackPipelines.PackParticle particlePipeline(ChimeraEntityBridge.Family family) {
        return switch (family) {
            case PARTICLE -> this.particle;
            case PARTICLE_TRANSLUCENT -> this.translucentParticle;
            default -> this.hostLayout.get(family);
        };
    }
}
