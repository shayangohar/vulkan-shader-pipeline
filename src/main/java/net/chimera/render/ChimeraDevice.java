package net.chimera.render;

import net.chimera.shaderpack.PackDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDriverProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceSubgroupProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

import java.nio.IntBuffer;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/**
 * What Chimera enables on VulkanMod's logical device (ChimeraDeviceFeaturesMixin). Chimera's
 * windows render with VK_KHR_dynamic_rendering, which VulkanMod leaves off; without it no shader
 * pack can run, and Chimera keeps VulkanMod's own rendering. The independentBlend feature lets a
 * window's attachments blend and mask writes differently. The features and extensions pack GLSL
 * compiles to are the PackDevice it is created as.
 */
public final class ChimeraDevice {
    /** A feature flag in a Vulkan features struct: its member name, read and written. */
    public record Feature<S>(String name, Predicate<S> read, BiConsumer<S, Boolean> write) {}

    /**
     * The VkPhysicalDeviceFeatures flags Chimera enables where the device offers them
     * (PackDevice.CORE_FEATURES, less VulkanMod's tessellationShader).
     */
    public static final List<Feature<VkPhysicalDeviceFeatures>> CORE_FEATURES = List.of(
            core("vertexPipelineStoresAndAtomics", VkPhysicalDeviceFeatures::vertexPipelineStoresAndAtomics,
                    VkPhysicalDeviceFeatures::vertexPipelineStoresAndAtomics),
            core("fragmentStoresAndAtomics", VkPhysicalDeviceFeatures::fragmentStoresAndAtomics,
                    VkPhysicalDeviceFeatures::fragmentStoresAndAtomics),
            core("shaderStorageImageExtendedFormats", VkPhysicalDeviceFeatures::shaderStorageImageExtendedFormats,
                    VkPhysicalDeviceFeatures::shaderStorageImageExtendedFormats),
            core("shaderStorageImageReadWithoutFormat", VkPhysicalDeviceFeatures::shaderStorageImageReadWithoutFormat,
                    VkPhysicalDeviceFeatures::shaderStorageImageReadWithoutFormat),
            core("shaderStorageImageWriteWithoutFormat",
                    VkPhysicalDeviceFeatures::shaderStorageImageWriteWithoutFormat,
                    VkPhysicalDeviceFeatures::shaderStorageImageWriteWithoutFormat),
            core("shaderStorageImageMultisample", VkPhysicalDeviceFeatures::shaderStorageImageMultisample,
                    VkPhysicalDeviceFeatures::shaderStorageImageMultisample),
            core("independentBlend", VkPhysicalDeviceFeatures::independentBlend,
                    VkPhysicalDeviceFeatures::independentBlend),
            core("geometryShader", VkPhysicalDeviceFeatures::geometryShader, VkPhysicalDeviceFeatures::geometryShader),
            core("shaderTessellationAndGeometryPointSize",
                    VkPhysicalDeviceFeatures::shaderTessellationAndGeometryPointSize,
                    VkPhysicalDeviceFeatures::shaderTessellationAndGeometryPointSize),
            core("shaderInt16", VkPhysicalDeviceFeatures::shaderInt16, VkPhysicalDeviceFeatures::shaderInt16),
            core("shaderInt64", VkPhysicalDeviceFeatures::shaderInt64, VkPhysicalDeviceFeatures::shaderInt64),
            core("shaderFloat64", VkPhysicalDeviceFeatures::shaderFloat64, VkPhysicalDeviceFeatures::shaderFloat64),
            core("shaderImageGatherExtended", VkPhysicalDeviceFeatures::shaderImageGatherExtended,
                    VkPhysicalDeviceFeatures::shaderImageGatherExtended),
            core("shaderClipDistance", VkPhysicalDeviceFeatures::shaderClipDistance,
                    VkPhysicalDeviceFeatures::shaderClipDistance),
            core("shaderCullDistance", VkPhysicalDeviceFeatures::shaderCullDistance,
                    VkPhysicalDeviceFeatures::shaderCullDistance),
            core("sampleRateShading", VkPhysicalDeviceFeatures::sampleRateShading,
                    VkPhysicalDeviceFeatures::sampleRateShading),
            core("imageCubeArray", VkPhysicalDeviceFeatures::imageCubeArray, VkPhysicalDeviceFeatures::imageCubeArray),
            core("shaderResourceMinLod", VkPhysicalDeviceFeatures::shaderResourceMinLod,
                    VkPhysicalDeviceFeatures::shaderResourceMinLod),
            core("multiViewport", VkPhysicalDeviceFeatures::multiViewport, VkPhysicalDeviceFeatures::multiViewport));
    /** The Vulkan 1.1 flags Chimera enables (PackDevice.VULKAN11_FEATURES, less VulkanMod's shaderDrawParameters). */
    public static final List<Feature<VkPhysicalDeviceVulkan11Features>> VULKAN11_FEATURES = List.of(
            vulkan11("storageBuffer16BitAccess", VkPhysicalDeviceVulkan11Features::storageBuffer16BitAccess,
                    VkPhysicalDeviceVulkan11Features::storageBuffer16BitAccess),
            vulkan11("uniformAndStorageBuffer16BitAccess",
                    VkPhysicalDeviceVulkan11Features::uniformAndStorageBuffer16BitAccess,
                    VkPhysicalDeviceVulkan11Features::uniformAndStorageBuffer16BitAccess),
            vulkan11("storagePushConstant16", VkPhysicalDeviceVulkan11Features::storagePushConstant16,
                    VkPhysicalDeviceVulkan11Features::storagePushConstant16),
            vulkan11("storageInputOutput16", VkPhysicalDeviceVulkan11Features::storageInputOutput16,
                    VkPhysicalDeviceVulkan11Features::storageInputOutput16));
    /** The Vulkan 1.2 flags Chimera enables (PackDevice.VULKAN12_FEATURES). */
    public static final List<Feature<VkPhysicalDeviceVulkan12Features>> VULKAN12_FEATURES = List.of(
            vulkan12("shaderFloat16", VkPhysicalDeviceVulkan12Features::shaderFloat16,
                    VkPhysicalDeviceVulkan12Features::shaderFloat16),
            vulkan12("shaderInt8", VkPhysicalDeviceVulkan12Features::shaderInt8,
                    VkPhysicalDeviceVulkan12Features::shaderInt8),
            vulkan12("storageBuffer8BitAccess", VkPhysicalDeviceVulkan12Features::storageBuffer8BitAccess,
                    VkPhysicalDeviceVulkan12Features::storageBuffer8BitAccess),
            vulkan12("uniformAndStorageBuffer8BitAccess",
                    VkPhysicalDeviceVulkan12Features::uniformAndStorageBuffer8BitAccess,
                    VkPhysicalDeviceVulkan12Features::uniformAndStorageBuffer8BitAccess),
            vulkan12("storagePushConstant8", VkPhysicalDeviceVulkan12Features::storagePushConstant8,
                    VkPhysicalDeviceVulkan12Features::storagePushConstant8),
            vulkan12("shaderSubgroupExtendedTypes", VkPhysicalDeviceVulkan12Features::shaderSubgroupExtendedTypes,
                    VkPhysicalDeviceVulkan12Features::shaderSubgroupExtendedTypes),
            vulkan12("subgroupBroadcastDynamicId", VkPhysicalDeviceVulkan12Features::subgroupBroadcastDynamicId,
                    VkPhysicalDeviceVulkan12Features::subgroupBroadcastDynamicId),
            vulkan12("shaderBufferInt64Atomics", VkPhysicalDeviceVulkan12Features::shaderBufferInt64Atomics,
                    VkPhysicalDeviceVulkan12Features::shaderBufferInt64Atomics),
            vulkan12("shaderSharedInt64Atomics", VkPhysicalDeviceVulkan12Features::shaderSharedInt64Atomics,
                    VkPhysicalDeviceVulkan12Features::shaderSharedInt64Atomics));

    private static volatile boolean dynamicRendering;
    private static volatile boolean independentBlend;
    private static volatile boolean geometryShader;

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

    /** {@code device} created with {@code features} and {@code extensions}, as pack GLSL compiles for it. */
    public static PackDevice describe(VkPhysicalDevice device, Collection<String> features,
                                      Collection<String> extensions) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceDriverProperties driver = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceSubgroupProperties subgroup = VkPhysicalDeviceSubgroupProperties.calloc(stack)
                    .sType$Default().pNext(driver.address());
            VkPhysicalDeviceProperties2 properties = VkPhysicalDeviceProperties2.calloc(stack).sType$Default()
                    .pNext(subgroup.address());
            vkGetPhysicalDeviceProperties2(device, properties);
            return new PackDevice("device", properties.properties().vendorID(), driver.driverID(),
                    properties.properties().deviceNameString(), new TreeSet<>(features),
                    subgroup.supportedStages(), subgroup.supportedOperations(), new TreeSet<>(extensions));
        }
    }

    private static Feature<VkPhysicalDeviceFeatures> core(String name, Predicate<VkPhysicalDeviceFeatures> read,
                                                          BiConsumer<VkPhysicalDeviceFeatures, Boolean> write) {
        return new Feature<>(name, read, write);
    }

    private static Feature<VkPhysicalDeviceVulkan11Features> vulkan11(
            String name, Predicate<VkPhysicalDeviceVulkan11Features> read,
            BiConsumer<VkPhysicalDeviceVulkan11Features, Boolean> write) {
        return new Feature<>(name, read, write);
    }

    private static Feature<VkPhysicalDeviceVulkan12Features> vulkan12(
            String name, Predicate<VkPhysicalDeviceVulkan12Features> read,
            BiConsumer<VkPhysicalDeviceVulkan12Features, Boolean> write) {
        return new Feature<>(name, read, write);
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
