package net.chimera.mixin;

import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Enables only supported core features needed by the bounded M8.6a image path. */
@Mixin(value = DeviceManager.class, remap = false)
public abstract class ChimeraDeviceFeaturesMixin {
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("chimera");

    @ModifyArg(
            method = "createLogicalDevice",
            at = @At(value = "INVOKE", target =
                    "Lorg/lwjgl/vulkan/VkDeviceCreateInfo;pEnabledFeatures("
                            + "Lorg/lwjgl/vulkan/VkPhysicalDeviceFeatures;)"
                            + "Lorg/lwjgl/vulkan/VkDeviceCreateInfo;"),
            index = 0,
            require = 1
    )
    private static VkPhysicalDeviceFeatures chimera$enableStorageFeatures(
            VkPhysicalDeviceFeatures enabled
    ) {
        Device device = DeviceManager.device;
        VkPhysicalDeviceFeatures available = device == null || device.availableFeatures == null
                ? null : device.availableFeatures.features();
        if (available == null) return enabled;

        int enabledCount = 0;
        if (available.vertexPipelineStoresAndAtomics()) {
            enabled.vertexPipelineStoresAndAtomics(true);
            enabledCount++;
        }
        if (available.fragmentStoresAndAtomics()) {
            enabled.fragmentStoresAndAtomics(true);
            enabledCount++;
        }
        if (available.shaderStorageImageExtendedFormats()) {
            enabled.shaderStorageImageExtendedFormats(true);
            enabledCount++;
        }
        if (available.shaderStorageImageWriteWithoutFormat()) {
            enabled.shaderStorageImageWriteWithoutFormat(true);
            enabledCount++;
        }
        if (enabledCount > 0) {
            LOGGER.info("[chimera] enabled {} supported storage-image device features", enabledCount);
        }
        return enabled;
    }

}
