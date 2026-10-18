package net.chimera.mixin;

import net.chimera.render.shader.ChimeraTerrainPipelines;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Removes Minecraft's directional face shading while the installed pack lights faces itself,
 * as Iris's MixinClientLevel does. VulkanMod's flat and smooth light pipelines and its fluid
 * renderer read the shade through RenderRegion.getShade, which calls this method, so terrain
 * and fluids mesh unshaded (1.0, or 0.9 in constant-ambient dimensions). Both overloads change,
 * NeoForge's per-normal one included.
 */
@Mixin(ClientLevel.class)
public abstract class ChimeraClientLevelShadeMixin {
    @ModifyVariable(method = "getShade", at = @At("HEAD"), argsOnly = true)
    private boolean chimera$packDirectionalShading(boolean shaded) {
        return shaded && !ChimeraTerrainPipelines.directionalShadingDisabled();
    }
}
