package net.chimera.render.shader;

import net.minecraft.client.renderer.RenderType;

/**
 * RenderType.lightning() as LightningBoltRenderer draws the bolt (ChimeraLightningBoltMixin), as
 * Iris wraps it in its LightningRenderStateShard: while it draws, the lightning lanes read the
 * pack's lightning_bolt id. Other users of RenderType.lightning() keep 0, and dragon rays draw
 * with their own render type.
 */
public final class LightningBoltRenderType extends RenderType {
    public static final RenderType INSTANCE = new LightningBoltRenderType(RenderType.lightning());

    private final RenderType host;

    private LightningBoltRenderType(RenderType lightning) {
        super("chimera_lightning_bolt", lightning.format(), lightning.mode(), lightning.bufferSize(),
                lightning.affectsCrumbling(), lightning.sortOnUpload(), () -> {
                    lightning.setupRenderState();
                    ChimeraEntityBridge.setLightningBoltDraw(true);
                }, () -> {
                    ChimeraEntityBridge.setLightningBoltDraw(false);
                    lightning.clearRenderState();
                });
        this.host = lightning;
    }

    /**
     * {@code type}, or for the bolt's type the RenderType.lightning() it draws as, whose shader and
     * transparency are the bolt's: Iris's InnerWrappedRenderType answers with its wrapped type's. Read
     * as no composite, the bolt was neither held for its lightning lane nor ordered as Iris's
     * GENERAL_TRANSPARENT batch, and drew with the entities, before RenderPearl's deferred pass: its
     * deferred-ignore flag then kept the unlit scene behind its 30% shells, and the bolt showed
     * white and solid where Iris blends it over the lit sky.
     */
    public static RenderType unwrap(RenderType type) {
        return type instanceof LightningBoltRenderType bolt ? bolt.host : type;
    }
}
