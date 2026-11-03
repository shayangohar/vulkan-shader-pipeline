package net.chimera.render;

import net.chimera.shaderpack.GlslVendorExtensions;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.IntBuffer;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;

/**
 * What Chimera enables on VulkanMod's logical device (ChimeraDeviceFeaturesMixin). Chimera's
 * windows render with VK_KHR_dynamic_rendering, which VulkanMod leaves off; without it no shader
 * pack can run, and Chimera keeps VulkanMod's own rendering. The independentBlend feature lets a
 * window's attachments blend and mask writes differently. The sized arithmetic types serve a pack's
 * GL vendor extensions (GlslVendorExtensions). The enabled device extensions bound which SPIR-V a
 * pipeline may be built from.
 */
public final class ChimeraDevice {
    private static volatile boolean dynamicRendering;
    private static volatile boolean independentBlend;
    private static volatile boolean geometryShader;
    private static volatile GlslVendorExtensions.SizedTypes sizedTypes = GlslVendorExtensions.SizedTypes.NONE;
    private static volatile Set<String> enabledExtensions;

    private ChimeraDevice() {}

    /** True once the device was created with dynamic rendering. */
    public static boolean dynamicRendering() {
        return dynamicRendering;
    }

    public static void setDynamicRendering(boolean enabled) {
        dynamicRendering = enabled;
    }

    /** True once the device was created with independentBlend. */
    public static boolean independentBlend() {
        return independentBlend;
    }

    public static void setIndependentBlend(boolean enabled) {
        independentBlend = enabled;
    }

    /** True once the device was created with geometryShader, which a pack's .gsh stage runs on. */
    public static boolean geometryShader() {
        return geometryShader;
    }

    public static void setGeometryShader(boolean enabled) {
        geometryShader = enabled;
    }

    /** The sized arithmetic types the device was created with, which vendor extensions map onto. */
    public static GlslVendorExtensions.SizedTypes sizedTypes() {
        return sizedTypes;
    }

    public static void setSizedTypes(GlslVendorExtensions.SizedTypes types) {
        sizedTypes = types;
    }

    public static void setEnabledExtensions(Collection<String> names) {
        enabledExtensions = Set.copyOf(names);
    }

    /**
     * The extensions in {@code required} the device was created without, which a module that
     * needs them must not reach the driver with (SpirvDeviceExtensions). None before the device
     * exists, as offline.
     */
    public static List<String> missingExtensions(Collection<String> required) {
        Set<String> enabled = enabledExtensions;
        if (enabled == null) return List.of();
        return required.stream().filter(name -> !enabled.contains(name)).toList();
    }

    /**
     * Whether the device serves GL_AMD_shader_trinary_minmax natively; without it the extension's
     * functions compile as min and max (GlslVendorExtensions). Offline, before a device, it does.
     */
    public static boolean trinaryMinmax() {
        return missingExtensions(List.of("VK_AMD_shader_trinary_minmax")).isEmpty();
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
