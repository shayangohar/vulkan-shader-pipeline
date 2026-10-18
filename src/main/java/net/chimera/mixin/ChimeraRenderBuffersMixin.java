package net.chimera.mixin;

import net.chimera.render.shader.ChimeraEntityBridge;
import net.chimera.render.shader.FamilyBufferSource;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While the pack renders the world, the level's buffer sources batch the family lanes apart from
 * the host's (FamilyBufferSource). The outline source wraps the same family source, so a glowing
 * entity keeps its pack program and its outline.
 */
@Mixin(RenderBuffers.class)
public abstract class ChimeraRenderBuffersMixin {
    @Shadow @Final private MultiBufferSource.BufferSource bufferSource;

    @Unique private FamilyBufferSource chimera$families;
    @Unique private OutlineBufferSource chimera$outlines;

    @Inject(method = "bufferSource", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$familyBuffers(CallbackInfoReturnable<MultiBufferSource.BufferSource> callback) {
        if (ChimeraEntityBridge.routesWorldBuffers()) callback.setReturnValue(chimera$families());
    }

    @Inject(method = "outlineBufferSource", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$familyOutlines(CallbackInfoReturnable<OutlineBufferSource> callback) {
        if (ChimeraEntityBridge.routesWorldBuffers()) {
            chimera$families();
            callback.setReturnValue(chimera$outlines);
        }
    }

    @Unique
    private FamilyBufferSource chimera$families() {
        if (chimera$families == null) {
            chimera$families = new FamilyBufferSource(bufferSource);
            chimera$outlines = new OutlineBufferSource(chimera$families);
        }
        return chimera$families;
    }
}
