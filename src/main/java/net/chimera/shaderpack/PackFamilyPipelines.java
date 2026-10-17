package net.chimera.shaderpack;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.chimera.render.PackStorageBufferOwner;
import net.chimera.render.vertex.ChimeraVertexFormats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the pipelines of the host geometry families a pack program can replace: entities, block
 * entities, hands and their glint, particles, and the host-layout lanes.
 */
public final class PackFamilyPipelines {
    private PackFamilyPipelines() {}

    /** Builds any world entity-family adapter with the family-specific host contract. */
    public static PackPipelines.PackEntity buildEntityFamily(PackProgramPlan plan) {
        return buildEntityFamily(plan, PackAdvancedResourcePlan.empty(), null);
    }

    public static PackPipelines.PackEntity buildEntityFamily(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        if (plan == null) {
            return null;
        }
        FamilyAdapterPlan.Family family = plan.familyAdapter().family();
        UniformRegistry.Stage stage = switch (family) {
            case BLOCK, BLOCK_TRANSLUCENT, DAMAGED_BLOCK -> UniformRegistry.Stage.BLOCK;
            case HAND, HAND_WATER -> UniformRegistry.Stage.HAND;
            default -> UniformRegistry.Stage.ENTITY;
        };
        VertexFormat format = switch (family) {
            case DAMAGED_BLOCK -> DefaultVertexFormat.BLOCK;
            case HAND, HAND_WATER -> ChimeraVertexFormats.EXTENDED_ENTITY;
            default -> ChimeraVertexFormats.EXTENDED_ENTITY;
        };
        return buildEntityLike(plan, stage, "pack_" + plan.name(), format, advancedResources, storageOwner);
    }

    /** Builds the block-entity adapter on the same append-only host format. */
    public static PackPipelines.PackEntity buildBlock(PackProgramPlan plan) {
        return buildEntityLike(plan, UniformRegistry.Stage.BLOCK, "pack_gbuffers_block",
                ChimeraVertexFormats.EXTENDED_ENTITY, PackAdvancedResourcePlan.empty(), null);
    }

    public static PackPipelines.PackEntity buildBlock(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return buildEntityLike(plan, UniformRegistry.Stage.BLOCK, "pack_gbuffers_block",
                ChimeraVertexFormats.EXTENDED_ENTITY, advancedResources, storageOwner);
    }

    /**
     * The same entity or block program built for another native host layout, as Iris
     * draws TEXT with IrisVertexFormats.GLYPH through gbuffers_entities_translucent and
     * TEXT_BE through gbuffers_block_translucent (falling back to gbuffers_block).
     */
    public static PackPipelines.PackEntity buildLayoutVariant(
            PackProgramPlan plan, VertexFormat host,
            PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        VertexFormat extended = host == null ? null : ChimeraVertexFormats.handFormats().get(host);
        if (plan == null || !plan.executable() || extended == null) return null;
        UniformRegistry.Stage stage = switch (plan.familyAdapter().family()) {
            case BLOCK, BLOCK_TRANSLUCENT, DAMAGED_BLOCK -> UniformRegistry.Stage.BLOCK;
            default -> UniformRegistry.Stage.ENTITY;
        };
        try {
            String vertex = EntityVertexBridge.layoutVertexForFormat(plan.convertedVertex(), host);
            return buildEntityLike(plan, stage, "pack_" + plan.name() + "_" + extended.getVertexSize(), extended,
                    advancedResources, storageOwner, vertex);
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
    }

    /** Builds the first-person hand adapter on the same append-only host format. */
    public static PackPipelines.PackHand buildHand(PackProgramPlan plan) {
        return buildHand(plan, PackAdvancedResourcePlan.empty(), null);
    }

    public static PackPipelines.PackHand buildHand(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        if (plan == null || !plan.executable()) return null;
        Map<VertexFormat, PackPipelines.PackEntity> variants = new LinkedHashMap<>();
        boolean complete = false;
        try {
            for (var entry : ChimeraVertexFormats.handFormats().entrySet()) {
                String vertex = EntityVertexBridge.handVertexForFormat(plan.convertedVertex(), entry.getKey());
                PackPipelines.PackEntity built = buildEntityLike(plan, UniformRegistry.Stage.HAND,
                        "pack_" + plan.name() + "_" + variants.size(), entry.getValue(),
                        advancedResources, storageOwner, vertex);
                if (built == null) return null;
                variants.put(entry.getKey(), built);
            }
            complete = true;
            return new PackPipelines.PackHand(variants);
        } finally {
            if (!complete) variants.values().forEach(value -> value.pipeline().cleanUp());
        }
    }

    static PackPipelines.PackEntity buildEntityLike(
            PackProgramPlan plan,
            UniformRegistry.Stage stage,
            String pipelineName,
            VertexFormat vertexFormat,
            PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return buildEntityLike(plan, stage, pipelineName, vertexFormat, advancedResources, storageOwner,
                plan == null ? null : plan.convertedVertex());
    }

    static PackPipelines.PackEntity buildEntityLike(
            PackProgramPlan plan, UniformRegistry.Stage stage, String pipelineName, VertexFormat vertexFormat,
            PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner, String convertedVertex
    ) {
        PackPipelines.BuiltGeometry built = PackPipelines.buildGeometry(plan, stage, pipelineName, vertexFormat,
                convertedVertex,
                advancedResources, storageOwner);
        return built == null ? null
                : new PackPipelines.PackEntity(built.pipeline(), built.samplerSlots(), built.vertexSource(),
                built.fragmentSource(), built.outputPlan(), built.imageBindings());
    }

    /** Builds the particle family on the host DefaultVertexFormat.PARTICLE path. */
    public static PackPipelines.PackParticle buildParticle(PackProgramPlan plan) {
        return buildParticle(plan, PackAdvancedResourcePlan.empty(), null);
    }

    public static PackPipelines.PackParticle buildParticle(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return buildParticle(plan, "pack_gbuffers_particles", DefaultVertexFormat.PARTICLE,
                advancedResources, storageOwner);
    }

    /** Builds an independent opaque or translucent particle-family pipeline. */
    public static PackPipelines.PackParticle buildParticleFamily(PackProgramPlan plan) {
        return buildParticleFamily(plan, PackAdvancedResourcePlan.empty(), null);
    }

    public static PackPipelines.PackParticle buildParticleFamily(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return plan == null ? null : buildParticle(plan, "pack_" + plan.name(),
                DefaultVertexFormat.PARTICLE, advancedResources, storageOwner);
    }

    /**
     * Builds a host-layout lane on the host vertex format of its contract: in the main pass
     * lines, glint, lightning, beacon beams and leads (Iris ShaderKey.LINES, GLINT, LIGHTNING,
     * BEACON and LEASH); in the shadow pass the shadow keys Iris draws on those layouts, into the
     * shadow map's attachments with blending off, as shadow_entities is built.
     */
    public static PackPipelines.PackParticle buildHostLayout(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        if (plan == null) return null;
        VertexFormat format = hostLayoutFormat(plan.familyAdapter().vertexContract());
        if (!plan.familyAdapter().stage().shadowPass()) {
            return buildParticle(plan, "pack_" + plan.name(), format, advancedResources, storageOwner);
        }
        PackPipelines.PackEntity shadow = buildEntityLike(plan, UniformRegistry.Stage.SHADOW_ENTITY,
                "pack_" + plan.name(),
                format, advancedResources, storageOwner);
        return shadow == null ? null : new PackPipelines.PackParticle(shadow.pipeline(), shadow.samplerSlots(),
                shadow.convertedVertex(), shadow.convertedFragment(), shadow.outputPlan(), shadow.imageBindings());
    }

    /** The host vertex format a host-layout contract draws on. */
    public static VertexFormat hostLayoutFormat(FamilyAdapterPlan.VertexContract contract) {
        return switch (contract) {
            case HOST_LINE -> DefaultVertexFormat.POSITION_COLOR_NORMAL;
            case HOST_GLINT -> DefaultVertexFormat.POSITION_TEX;
            case HOST_LIGHTNING -> DefaultVertexFormat.POSITION_COLOR;
            case HOST_BEACON -> DefaultVertexFormat.BLOCK;
            case HOST_LEASH, HOST_TEXT_BACKGROUND -> DefaultVertexFormat.POSITION_COLOR_LIGHTMAP;
            case HOST_TEXT -> DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP;
            case HOST_WATER_MASK -> DefaultVertexFormat.POSITION;
            default -> throw new IllegalArgumentException("not a host-layout contract: " + contract);
        };
    }

    /** The same glint program for first-person glint, under the hand projection. */
    public static PackPipelines.PackParticle buildHandGlint(
            PackProgramPlan plan, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        if (plan == null || plan.convertedVertex() == null) return null;
        String vertex;
        try {
            vertex = EntityVertexBridge.handGlintVertex(plan.convertedVertex());
        } catch (IllegalArgumentException unsupported) {
            return null;
        }
        return buildParticle(plan, "pack_" + plan.name() + "_hand",
                DefaultVertexFormat.POSITION_TEX, vertex, advancedResources, storageOwner);
    }

    static PackPipelines.PackParticle buildParticle(
            PackProgramPlan plan, String pipelineName, VertexFormat format,
            PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        return buildParticle(plan, pipelineName, format, plan == null ? null : plan.convertedVertex(),
                advancedResources, storageOwner);
    }

    static PackPipelines.PackParticle buildParticle(
            PackProgramPlan plan, String pipelineName, VertexFormat format,
            String convertedVertex, PackAdvancedResourcePlan advancedResources, PackStorageBufferOwner storageOwner
    ) {
        PackPipelines.BuiltGeometry built = PackPipelines.buildGeometry(plan, UniformRegistry.Stage.PARTICLE,
                pipelineName, format,
                convertedVertex, advancedResources, storageOwner);
        return built == null ? null
                : new PackPipelines.PackParticle(built.pipeline(), built.samplerSlots(), built.vertexSource(),
                built.fragmentSource(), built.outputPlan(), built.imageBindings());
    }
}
