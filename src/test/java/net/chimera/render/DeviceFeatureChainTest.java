package net.chimera.render;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBaseOutStructure;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
import static org.lwjgl.vulkan.VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
import static org.lwjgl.vulkan.VK13.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;

/**
 * Chimera's device features join the structs VulkanMod's create info already chains, each struct
 * type once (audit LD5): Reforged 0.3.5 chains its Vulkan 1.1 struct alone, and a host that chained
 * its own 1.2, 1.3 or dynamic rendering struct would have met a second one.
 */
final class DeviceFeatureChainTest {
    private static final Set<String> VULKAN12 = Set.of("shaderFloat16", "shaderInt8");

    @Test
    void reforgedsChainGainsChimerasStructs() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack).sType$Default()
                    .pNext(VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default());
            DeviceFeatureChain.apply(info, stack, VULKAN12, true);
            assertEquals(List.of(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR,
                    VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES,
                    VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES), sTypes(info));
            VkPhysicalDeviceVulkan12Features vulkan12 = VkPhysicalDeviceVulkan12Features.create(
                    DeviceFeatureChain.find(info.pNext(), VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES));
            assertTrue(vulkan12.shaderFloat16() && vulkan12.shaderInt8());
            assertFalse(vulkan12.storageBuffer8BitAccess(), "only the offered features");
            assertTrue(VkPhysicalDeviceDynamicRenderingFeaturesKHR.create(DeviceFeatureChain.find(info.pNext(),
                    VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR)).dynamicRendering());
        }
    }

    @Test
    void aHostsOwnStructsAreMergedInto() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceVulkan12Features hostVulkan12 = VkPhysicalDeviceVulkan12Features.calloc(stack)
                    .sType$Default().descriptorIndexing(true);
            VkPhysicalDeviceDynamicRenderingFeaturesKHR hostDynamic = VkPhysicalDeviceDynamicRenderingFeaturesKHR
                    .calloc(stack).sType$Default();
            VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack).sType$Default()
                    .pNext(hostVulkan12).pNext(hostDynamic);
            DeviceFeatureChain.apply(info, stack, VULKAN12, true);
            assertEquals(List.of(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DYNAMIC_RENDERING_FEATURES_KHR,
                    VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES), sTypes(info));
            assertTrue(hostVulkan12.descriptorIndexing() && hostVulkan12.shaderFloat16()
                    && hostVulkan12.shaderInt8(), "the host's features stay, Chimera's join them");
            assertTrue(hostDynamic.dynamicRendering());
        }
    }

    @Test
    void aVulkan13StructTakesDynamicRenderingItself() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceVulkan13Features hostVulkan13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                    .sType$Default();
            VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack).sType$Default().pNext(hostVulkan13);
            DeviceFeatureChain.apply(info, stack, Set.of(), true);
            assertEquals(List.of(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES), sTypes(info),
                    "no dynamic rendering struct beside a Vulkan 1.3 one, and no empty 1.2 struct");
            assertTrue(hostVulkan13.dynamicRendering());
        }
    }

    @Test
    void aDeviceWithoutDynamicRenderingGetsNoStruct() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack).sType$Default();
            DeviceFeatureChain.apply(info, stack, Set.of(), false);
            assertEquals(NULL, info.pNext());
        }
    }

    private static List<Integer> sTypes(VkDeviceCreateInfo info) {
        List<Integer> types = new ArrayList<>();
        for (VkBaseOutStructure at = info.pNext() == NULL ? null : VkBaseOutStructure.create(info.pNext());
             at != null; at = at.pNext()) {
            types.add(at.sType());
        }
        return types;
    }
}
