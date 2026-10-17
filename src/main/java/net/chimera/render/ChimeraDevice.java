package net.chimera.render;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.IntBuffer;

import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;

/**
 * What Chimera enables on VulkanMod's logical device (ChimeraDeviceFeaturesMixin). Chimera's
 * windows render with VK_KHR_dynamic_rendering, which VulkanMod leaves off; without it no shader
 * pack can run, and Chimera keeps VulkanMod's own rendering.
 */
public final class ChimeraDevice {
    private static volatile boolean dynamicRendering;

    private ChimeraDevice() {}

    /** True once the device was created with dynamic rendering. */
    public static boolean dynamicRendering() {
        return dynamicRendering;
    }

    public static void setDynamicRendering(boolean enabled) {
        dynamicRendering = enabled;
    }

    /** Whether {@code device} offers the device extension {@code name}. */
    public static boolean supportsExtension(VkPhysicalDevice device, String name) {
        if (device == null) return false;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.ints(0);
            vkEnumerateDeviceExtensionProperties(device, (String) null, count, null);
            // A device lists a few hundred extensions of 260 bytes each: too many for the stack.
            try (VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(count.get(0))) {
                vkEnumerateDeviceExtensionProperties(device, (String) null, count, extensions);
                for (VkExtensionProperties extension : extensions) {
                    if (name.equals(extension.extensionNameString())) return true;
                }
                return false;
            }
        }
    }
}
