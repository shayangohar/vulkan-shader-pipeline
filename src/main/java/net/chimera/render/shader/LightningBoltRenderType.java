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

    private LightningBoltRenderType(RenderType lightning) {
        super("chimera_lightning_bolt", lightning.format(), lightning.mode(), lightning.bufferSize(),
                lightning.affectsCrumbling(), lightning.sortOnUpload(), () -> {
                    lightning.setupRenderState();
                    ChimeraEntityBridge.setLightningBoltDraw(true);
                }, () -> {
                    ChimeraEntityBridge.setLightningBoltDraw(false);
                    lightning.clearRenderState();
                });
    }
}
