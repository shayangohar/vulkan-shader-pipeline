package net.chimera.render.shader;

import net.chimera.shaderpack.PackEntityIdResolver;
import net.chimera.shaderpack.PackPipelines;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * The pack pipelines ChimeraEntityBridge's lanes draw with, with the entity ids they read: one
 * immutable set a pack load installs whole and a pack cleanup drops whole ({@link #NONE}), so no
 * lane outlives the session that installed it. A null pipeline leaves its lane to the host draw, or
 * to the next program in Iris's chain (shadow_block to shadow_entities, gbuffers_block_translucent
 * to gbuffers_block).
 *
 * @param entity gbuffers_entities
 * @param translucentEntity gbuffers_entities_translucent
 * @param translucentEntityText gbuffers_entities_translucent on Iris's GLYPH text layout (TEXT)
 * @param spiderEyes gbuffers_spidereyes
 * @param block gbuffers_block
 * @param blockTranslucent gbuffers_block_translucent (BE_TRANSLUCENT)
 * @param blockTranslucentText gbuffers_block_translucent on the GLYPH layout (TEXT_BE)
 * @param movingBlock gbuffers_block on the block layout (MOVING_BLOCK)
 * @param damagedBlock gbuffers_damagedblock on the widened block layout
 * @param beaconBeam gbuffers_beaconbeam on the widened block layout (BEACON)
 * @param hand gbuffers_hand, per host format
 * @param handWater gbuffers_hand_water, per host format
 * @param particle gbuffers_particles
 * @param translucentParticle gbuffers_particles_translucent
 * @param hostLayout the {@link HostLayoutLane} pipelines, by family
 * @param shadowEntities shadow_entities: the shadow pass's entity-format casters
 * @param shadowMovingBlock shadow_entities on the block layout: the shadow pass's moving blocks
 * @param shadowBlock shadow_block: a block entity's models in the shadow pass
 * @param entityIds the pack's entity.properties ids
 */
public record InstalledLanes(
        GraphicsPipeline entity,
        GraphicsPipeline translucentEntity,
        GraphicsPipeline translucentEntityText,
        GraphicsPipeline spiderEyes,
        GraphicsPipeline block,
        GraphicsPipeline blockTranslucent,
        GraphicsPipeline blockTranslucentText,
        GraphicsPipeline movingBlock,
        GraphicsPipeline damagedBlock,
        GraphicsPipeline beaconBeam,
        PackPipelines.PackHand hand,
        PackPipelines.PackHand handWater,
        GraphicsPipeline particle,
        GraphicsPipeline translucentParticle,
        Map<ChimeraEntityBridge.Family, GraphicsPipeline> hostLayout,
        GraphicsPipeline shadowEntities,
        GraphicsPipeline shadowMovingBlock,
        GraphicsPipeline shadowBlock,
        PackEntityIdResolver entityIds
) {
    /** No pack: every lane draws with the host. */
    public static final InstalledLanes NONE = new InstalledLanes(null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, Map.of(), null, null, null, null);

    /**
     * Keeps only the host-layout families that have a lane and a pipeline, in family order; no ids
     * read as empty.
     */
    public InstalledLanes {
        EnumMap<ChimeraEntityBridge.Family, GraphicsPipeline> lanes = new EnumMap<>(ChimeraEntityBridge.Family.class);
        if (hostLayout != null) {
            hostLayout.forEach((family, pipeline) -> {
                if (HostLayoutLane.of(family) != null && pipeline != null) lanes.put(family, pipeline);
            });
        }
        hostLayout = Collections.unmodifiableMap(lanes);
        entityIds = entityIds == null ? PackEntityIdResolver.empty() : entityIds;
    }

    /**
     * Whether the bridge turns on for this set (ChimeraEntityBridge.isInstalled): one of the entity,
     * block, spider-eyes, damaged-block, beacon-beam, hand, particle, host-layout or shadow_entities
     * lanes has a program. The text, moving-block and block-translucent variants and shadow_block
     * do not count: a session whose only lane programs are among them leaves the bridge off, as it
     * did before the lanes were one set.
     */
    public boolean any() {
        return this.entity != null || this.translucentEntity != null || this.spiderEyes != null || this.block != null
                || this.damagedBlock != null || this.hand != null || this.handWater != null || this.particle != null
                || this.translucentParticle != null || this.beaconBeam != null || !this.hostLayout.isEmpty()
                || this.shadowEntities != null;
    }
}
