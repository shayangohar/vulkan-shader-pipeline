package net.chimera.render.shader;

import net.chimera.mixin.ChimeraCompositeRenderTypeAccessor;
import net.chimera.mixin.ChimeraCompositeStateAccessor;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;

/**
 * Iris's batched-entity transparency of a render type (batchedentityrendering TransparencyType). With
 * a pack's separateEntityDraws, the entity batches of the opaque kinds draw before the deferred passes
 * and the rest after them (batchedentityrendering MixinLevelRenderer).
 */
public enum IrisTransparency {
    OPAQUE, OPAQUE_DECAL, GENERAL_TRANSPARENT, DECAL, WATER_MASK, LINES;

    /**
     * The type's transparency as Iris sets it (MixinSheets, then MixinCompositeRenderType): a type that
     * is no composite reads GENERAL_TRANSPARENT, Iris's default for any render type. The bolt's type
     * reads the lightning type it wraps, as Iris's InnerWrappedRenderType does.
     */
    public static IrisTransparency of(RenderType type) {
        RenderType drawn = LightningBoltRenderType.unwrap(type);
        if (drawn == Sheets.armorTrimsSheet(false)) return OPAQUE_DECAL;
        if (drawn == RenderType.textBackground() || drawn == RenderType.textBackgroundSeeThrough()) return OPAQUE;
        if (drawn == RenderType.dragonRaysDepth()) return DECAL;
        if (!(drawn instanceof ChimeraCompositeRenderTypeAccessor composite)) return GENERAL_TRANSPARENT;
        var state = (ChimeraCompositeStateAccessor) (Object) composite.chimera$state();
        return ofComposite(drawn.name, state.chimera$transparencyState(), state.chimera$depthTestState());
    }

    /** A composite type's transparency from its name and its transparency and depth-test shards. */
    static IrisTransparency ofComposite(String name, RenderStateShard.TransparencyStateShard transparency,
                                        RenderStateShard.DepthTestStateShard depthTest) {
        if ("water_mask".equals(name) || depthTest == RenderStateShard.NO_DEPTH_TEST) return WATER_MASK;
        if ("lines".equals(name)) return LINES;
        if (transparency == RenderStateShard.NO_TRANSPARENCY) return OPAQUE;
        if (transparency == RenderStateShard.GLINT_TRANSPARENCY
                || transparency == RenderStateShard.CRUMBLING_TRANSPARENCY) {
            return DECAL;
        }
        return GENERAL_TRANSPARENT;
    }

    /** Whether a pack with separateEntityDraws draws this kind's batches before the deferred passes. */
    public boolean drawsBeforeDeferred() {
        return this == OPAQUE || this == OPAQUE_DECAL || this == WATER_MASK;
    }
}
