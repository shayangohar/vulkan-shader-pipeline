package net.chimera.mixin;

import net.chimera.render.ChimeraDevice;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME;

/**
 * Enables what Chimera needs on VulkanMod's logical device: the supported core features of the
 * pack image path, and VK_KHR_dynamic_rendering with its feature, which Chimera's windows render
 * with (ChimeraDevice).
 */
@Mixin(value = DeviceManager.class, remap = false)
public abstract class ChimeraDeviceFeaturesMixin {
    private static final Logger LOGGER =
            LoggerFactory.getLogger("chimera");

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

    /** Chains the dynamic rendering feature after VulkanMod's own feature structs, when the device offers it. */
    @Redirect(
            method = "createLogicalDevice",
            at = @At(value = "INVOKE", target =
                    "Lorg/lwjgl/vulkan/VkDeviceCreateInfo;pNext("
                            + "Lorg/lwjgl/vulkan/VkPhysicalDeviceVulkan11Features;)"
                            + "Lorg/lwjgl/vulkan/VkDeviceCreateInfo;"),
            require = 1
    )
    private static VkDeviceCreateInfo chimera$chainDynamicRendering(
            VkDeviceCreateInfo info, VkPhysicalDeviceVulkan11Features vulkan11
    ) {
        info.pNext(vulkan11);
        boolean supported = ChimeraDevice.supportsExtension(DeviceManager.physicalDevice,
                VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);
        ChimeraDevice.setDynamicRendering(supported);
        if (supported) {
            // The struct lives on the caller's stack frame, which outlasts vkCreateDevice.
            info.pNext(VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(MemoryStack.stackGet())
                    .sType$Default().dynamicRendering(true));
        } else {
            LOGGER.error("[chimera] the device lacks {}; shader packs cannot run",
                    VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);
        }
        return info;
    }

    @ModifyArg(
            method = "createLogicalDevice",
            at = @At(value = "INVOKE", target =
                    "Lorg/lwjgl/vulkan/VkDeviceCreateInfo;ppEnabledExtensionNames("
                            + "Lorg/lwjgl/PointerBuffer;)Lorg/lwjgl/vulkan/VkDeviceCreateInfo;"),
            index = 0,
            require = 1
    )
    private static PointerBuffer chimera$enableDynamicRenderingExtension(PointerBuffer extensions) {
        if (!ChimeraDevice.dynamicRendering()) return extensions;
        MemoryStack stack = MemoryStack.stackGet();
        PointerBuffer withDynamicRendering = stack.mallocPointer(extensions.remaining() + 1);
        for (int index = extensions.position(); index < extensions.limit(); index++) {
            if (VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME.equals(extensions.getStringUTF8(index))) {
                return extensions;
            }
            withDynamicRendering.put(extensions.get(index));
        }
        withDynamicRendering.put(stack.UTF8(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME));
        return withDynamicRendering.flip();
    }
}
