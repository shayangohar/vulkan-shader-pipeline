package net.chimera.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBaseOutStructure;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;

import java.util.Collection;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;

/**
 * Puts Chimera's Vulkan 1.2 features and dynamic rendering on the device VulkanMod creates, into
 * the structs its pNext chain already holds. A chain may hold each struct type once
 * (VUID-VkDeviceCreateInfo-sType-unique), and one with VkPhysicalDeviceVulkan13Features may not
 * also hold the dynamic rendering struct (VUID-VkDeviceCreateInfo-pNext-06532). Reforged 0.3.5 chains
 * only its Vulkan 1.1 struct, so Chimera's structs are added; a host that chains its own is
 * merged into instead. A host's single-feature struct that the 1.2 struct covers (such as
 * VkPhysicalDevice8BitStorageFeatures) would still conflict with it; Reforged chains none.
 */
public final class DeviceFeatureChain {
    private DeviceFeatureChain() {}

    /**
     * Enables the ChimeraDevice.VULKAN12_FEATURES named in {@code offered} and, when
     * {@code dynamicRendering}, the dynamic rendering feature on {@code info}'s chain. New structs
     * are allocated on {@code stack}, whose frame must outlast vkCreateDevice.
     */
    public static void apply(VkDeviceCreateInfo info, MemoryStack stack, Collection<String> offered,
                             boolean dynamicRendering) {
        List<ChimeraDevice.Feature<VkPhysicalDeviceVulkan12Features>> vulkan12 = ChimeraDevice.VULKAN12_FEATURES
                .stream().filter(feature -> offered.contains(feature.name())).toList();
        if (!vulkan12.isEmpty()) {
            long chained = find(info.pNext(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES);
            VkPhysicalDeviceVulkan12Features features = chained != NULL
                    ? VkPhysicalDeviceVulkan12Features.create(chained)
                    : VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            vulkan12.forEach(feature -> feature.write().accept(features, true));
            if (chained == NULL) info.pNext(features);
        }
        if (dynamicRendering) enableDynamicRendering(info, stack);
    }

    private static void enableDynamicRendering(VkDeviceCreateInfo info, MemoryStack stack) {
        long vulkan13 = find(info.pNext(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES);
        if (vulkan13 != NULL) {
            VkPhysicalDeviceVulkan13Features.create(vulkan13).dynamicRendering(true);
            return;
        }
        long chained = find(info.pNext(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR);
        if (chained != NULL) {
            VkPhysicalDeviceDynamicRenderingFeaturesKHR.create(chained).dynamicRendering(true);
        } else {
            info.pNext(VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack).sType$Default()
                    .dynamicRendering(true));
        }
    }

    /** The address of the first struct of {@code sType} in the chain from {@code next}, or NULL. */
    static long find(long next, int sType) {
        VkBaseOutStructure at = next == NULL ? null : VkBaseOutStructure.create(next);
        while (at != null) {
            if (at.sType() == sType) return at.address();
            at = at.pNext();
        }
        return NULL;
    }
}
