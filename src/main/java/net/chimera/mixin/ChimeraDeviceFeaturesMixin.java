package net.chimera.mixin;

import net.chimera.render.ChimeraDevice;
import net.chimera.shaderpack.GlslVendorExtensions;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceShaderFloat16Int8Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/**
 * Enables what Chimera needs on VulkanMod's logical device: the supported core features of the
 * pack image path, independentBlend for per-attachment blend and write masks, geometryShader for
 * a pack program's .gsh stage, the sized arithmetic types a pack's GL vendor extensions provide,
 * VK_KHR_dynamic_rendering with its feature, which Chimera's windows render with (ChimeraDevice), and
 * the device extensions pack GLSL compiles to.
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
    private static VkPhysicalDeviceFeatures chimera$enablePackFeatures(
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
        // A pack's blend directives and the buffers a program leaves unwritten differ per attachment.
        enabled.independentBlend(available.independentBlend());
        ChimeraDevice.setIndependentBlend(available.independentBlend());
        if (!available.independentBlend()) {
            LOGGER.warn("[chimera] no independentBlend: a pass whose attachments resolve to different "
                    + "blends draws unblended, and every attachment takes color writes");
        }
        // A pack program's .gsh stage; Iris's Vulkan path refuses one without the feature too.
        enabled.geometryShader(available.geometryShader());
        ChimeraDevice.setGeometryShader(available.geometryShader());
        // The sized arithmetic types a pack's GL vendor extensions provide (GlslVendorExtensions).
        enabled.shaderInt16(available.shaderInt16());
        enabled.shaderInt64(available.shaderInt64());
        ChimeraDevice.setSizedTypes(availableSizedTypes(available));
        return enabled;
    }

    /**
     * The sized arithmetic types the device offers. shaderFloat16 is in a Vulkan 1.2 feature struct
     * VulkanMod does not query.
     */
    private static GlslVendorExtensions.SizedTypes availableSizedTypes(VkPhysicalDeviceFeatures available) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceShaderFloat16Int8Features float16 =
                    VkPhysicalDeviceShaderFloat16Int8Features.calloc(stack).sType$Default();
            vkGetPhysicalDeviceFeatures2(DeviceManager.physicalDevice,
                    VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(float16.address()));
            return new GlslVendorExtensions.SizedTypes(float16.shaderFloat16(), available.shaderInt16(),
                    available.shaderInt64());
        }
    }

    /**
     * Chains the dynamic rendering feature, when the device offers it, and half floats, when it
     * offers them, after VulkanMod's own feature structs.
     */
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
        if (ChimeraDevice.sizedTypes().float16()) {
            // Like the dynamic rendering struct below, it lives on the caller's stack frame.
            info.pNext(VkPhysicalDeviceShaderFloat16Int8Features.calloc(MemoryStack.stackGet()).sType$Default()
                    .shaderFloat16(true));
        }
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

    /**
     * Device extensions that pack GLSL compiles to and that need no feature struct: RenderPearl's
     * GL_AMD_shader_trinary_minmax, Alpha Piscium's GL_NV_shader_subgroup_partitioned, and GLSL
     * 4.60's anyInvocation, allInvocations and allInvocationsEqual, which RenderPearl 2.8 calls
     * without GL_KHR_shader_subgroup. A module that needs one the device lacks fails its compile
     * (GlslCompiler).
     */
    private static final List<String> PACK_EXTENSIONS = List.of("VK_AMD_shader_trinary_minmax",
            "VK_NV_shader_subgroup_partitioned", "VK_EXT_shader_subgroup_vote");

    /**
     * Adds VK_KHR_dynamic_rendering and the offered pack extensions to VulkanMod's list, and
     * records the list the device is created with (ChimeraDevice.missingExtensions).
     */
    @ModifyArg(
            method = "createLogicalDevice",
            at = @At(value = "INVOKE", target =
                    "Lorg/lwjgl/vulkan/VkDeviceCreateInfo;ppEnabledExtensionNames("
                            + "Lorg/lwjgl/PointerBuffer;)Lorg/lwjgl/vulkan/VkDeviceCreateInfo;"),
            index = 0,
            require = 1
    )
    private static PointerBuffer chimera$enablePackExtensions(PointerBuffer extensions) {
        Set<String> names = new LinkedHashSet<>();
        for (int index = extensions.position(); index < extensions.limit(); index++) {
            names.add(extensions.getStringUTF8(index));
        }
        List<String> added = new ArrayList<>();
        if (ChimeraDevice.dynamicRendering() && names.add(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)) {
            added.add(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);
        }
        for (String pack : PACK_EXTENSIONS) {
            if (!names.contains(pack) && ChimeraDevice.supportsExtension(DeviceManager.physicalDevice, pack)) {
                names.add(pack);
                added.add(pack);
            }
        }
        ChimeraDevice.setEnabledExtensions(names);
        LOGGER.info("[chimera] device extensions added for shader packs: {}", added);
        if (added.isEmpty()) return extensions;
        MemoryStack stack = MemoryStack.stackGet();
        PointerBuffer withPackExtensions = stack.mallocPointer(extensions.remaining() + added.size());
        for (int index = extensions.position(); index < extensions.limit(); index++) {
            withPackExtensions.put(extensions.get(index));
        }
        added.forEach(name -> withPackExtensions.put(stack.UTF8(name)));
        return withPackExtensions.flip();
    }
}
