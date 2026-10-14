package net.chimera.mixin;

import net.chimera.render.PackCloudRenderer;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.vulkanmod.render.sky.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Takes VulkanMod's cloud draw while a pack cloud program is active. A program that provably
 * draws nothing skips the clouds, as Iris's run of it outputs nothing; any other pack program
 * draws vanilla's cloud faces through PackCloudRenderer. Drawing VulkanMod's own mesh instead
 * would overlay vanilla clouds on the pack's, or hand the program faces without normals.
 */
@Mixin(value = CloudRenderer.class, remap = false)
public abstract class ChimeraCloudRendererMixin {
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, require = 1)
    private void chimera$packClouds(float cloudHeight, int cloudColor, double cameraX, double cameraY,
                                    double cameraZ, long gameTime, float partialTick, CallbackInfo callback) {
        if (ChimeraSkyBridge.skipHostClouds() || PackCloudRenderer.render(cloudHeight, cloudColor,
                cameraX, cameraY, cameraZ, gameTime, partialTick)) {
            callback.cancel();
        }
    }
}
