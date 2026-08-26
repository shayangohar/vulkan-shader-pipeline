package net.chimera.mixin;

import net.chimera.render.ChimeraMainPass;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drives chimera's HDR segment inside LevelRenderer.renderLevel:
 *
 * - HEAD: opens the HDR segment (world renders into RGBA16F) and arms the
 *   shadow-pending flag on the main pass.
 * - The shadow segment itself renders at the tail of VulkanMod's SOLID
 *   section layer (WorldRendererMixin): by then cullTerrain has filled the
 *   section draw queues and the SOLID pass has just drawn from them, so the
 *   shadow phase sees exactly the state the solid pass drew from. The old
 *   hook here (INVOKE compileSections) raced section-graph updates and
 *   recorded zero draws; it is gone.
 */
@Mixin(LevelRenderer.class)
public abstract class ChimeraLevelRendererMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void chimera$openHdrSegment(CallbackInfo ci) {
        if (ChimeraRenderer.segmentsActive()) {
            ChimeraMainPass pass = ChimeraRenderer.getMainPass();
            if (pass != null) {
                pass.openLevelSegment();
            }
        }
    }
}
