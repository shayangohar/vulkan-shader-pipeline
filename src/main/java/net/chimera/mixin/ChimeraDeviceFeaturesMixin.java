package net.chimera.mixin;

import net.chimera.render.ChimeraDevice;
import net.chimera.shaderpack.PackDevice;
import net.vulkanmod.vulkan.device.Device;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan11Features;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.lwjgl.vulkan.KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2;

/**
 * Enables what Chimera needs on VulkanMod's logical device: the features pack GLSL compiles to,
 * where the device offers them, as a GL driver offers them all (ChimeraDevice's tables), among them
 * independentBlend for per-attachment blend and write masks and geometryShader for a pack
 * program's .gsh stage; VK_KHR_dynamic_rendering with its feature, which Chimera's windows render
 * with (ChimeraDevice); and the device extensions pack GLSL compiles to. The device it creates is
 * the PackDevice pack GLSL compiles for.
 */
@Mixin(value = DeviceManager.class, remap = false)
public abstract class ChimeraDeviceFeaturesMixin {
    private static final Logger LOGGER =
            LoggerFactory.getLogger("chimera");

    /** The Vulkan 1.1 and 1.2 features the device offers, read with its core features. */
    @Unique
    private static final Set<String> chimera$offered = new TreeSet<>();
    /** The features the device is created with, VulkanMod's among them. */
    @Unique
    private static final Set<String> chimera$features = new TreeSet<>();

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
        chimera$offered.clear();
        chimera$features.clear();
        Device device = DeviceManager.device;
        VkPhysicalDeviceFeatures available = device == null || device.availableFeatures == null
                ? null : device.availableFeatures.features();
        if (available == null) return enabled;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceVulkan12Features vulkan12 = VkPhysicalDeviceVulkan12Features.calloc(stack).sType$Default();
            VkPhysicalDeviceVulkan11Features vulkan11 = VkPhysicalDeviceVulkan11Features.calloc(stack).sType$Default()
                    .pNext(vulkan12.address());
            vkGetPhysicalDeviceFeatures2(DeviceManager.physicalDevice,
                    VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(vulkan11.address()));
            ChimeraDevice.VULKAN11_FEATURES.stream().filter(feature -> feature.read().test(vulkan11))
                    .forEach(feature -> chimera$offered.add(feature.name()));
            ChimeraDevice.VULKAN12_FEATURES.stream().filter(feature -> feature.read().test(vulkan12))
                    .forEach(feature -> chimera$offered.add(feature.name()));
        }
        for (ChimeraDevice.Feature<VkPhysicalDeviceFeatures> feature : ChimeraDevice.CORE_FEATURES) {
            if (feature.read().test(available)) feature.write().accept(enabled, true);
            if (feature.read().test(enabled)) chimera$features.add(feature.name());
        }
        if (enabled.tessellationShader()) chimera$features.add("tessellationShader");
        // A pack's blend directives and the buffers a program leaves unwritten differ per attachment.
        ChimeraDevice.setIndependentBlend(enabled.independentBlend());
        if (!enabled.independentBlend()) {
            LOGGER.warn("[chimera] no independentBlend: a pass whose attachments resolve to different "
                    + "blends draws unblended, and every attachment takes color writes");
        }
        // A pack program's .gsh stage; Iris's Vulkan path refuses one without the feature too.
        ChimeraDevice.setGeometryShader(enabled.geometryShader());
        return enabled;
    }

    /**
     * Enables the offered Vulkan 1.1 features on VulkanMod's struct and chains the offered Vulkan
     * 1.2 features and, when the device offers it, the dynamic rendering feature after it.
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
        for (ChimeraDevice.Feature<VkPhysicalDeviceVulkan11Features> feature : ChimeraDevice.VULKAN11_FEATURES) {
            if (chimera$offered.contains(feature.name())) feature.write().accept(vulkan11, true);
            if (feature.read().test(vulkan11)) chimera$features.add(feature.name());
        }
        if (vulkan11.shaderDrawParameters()) chimera$features.add("shaderDrawParameters");
        // Like the dynamic rendering struct below, it lives on the caller's stack frame.
        VkPhysicalDeviceVulkan12Features vulkan12 = VkPhysicalDeviceVulkan12Features.calloc(MemoryStack.stackGet())
                .sType$Default();
        for (ChimeraDevice.Feature<VkPhysicalDeviceVulkan12Features> feature : ChimeraDevice.VULKAN12_FEATURES) {
            if (!chimera$offered.contains(feature.name())) continue;
            feature.write().accept(vulkan12, true);
            chimera$features.add(feature.name());
        }
        if (ChimeraDevice.VULKAN12_FEATURES.stream().anyMatch(feature -> feature.read().test(vulkan12))) {
            info.pNext(vulkan12);
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
     * Adds VK_KHR_dynamic_rendering and the offered pack extensions (PackDevice.PACK_EXTENSIONS) to
     * VulkanMod's list, and records the device it creates as the PackDevice. A module that needs an
     * extension the device lacks fails its compile (GlslCompiler).
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
        for (String pack : PackDevice.PACK_EXTENSIONS) {
            if (!names.contains(pack) && ChimeraDevice.supportsExtension(DeviceManager.physicalDevice, pack)) {
                names.add(pack);
                added.add(pack);
            }
        }
        PackDevice packDevice = ChimeraDevice.describe(DeviceManager.physicalDevice, chimera$features, names);
        PackDevice.set(packDevice);
        LOGGER.info("[chimera] device extensions added for shader packs: {}", added);
        LOGGER.info("[chimera] pack device {} (vendor 0x{}, driver {}): features {}, subgroup stages 0x{}"
                        + " operations 0x{}", packDevice.name(), Integer.toHexString(packDevice.vendorId()),
                packDevice.driverId(), packDevice.features(), Integer.toHexString(packDevice.subgroupStages()),
                Integer.toHexString(packDevice.subgroupOperations()));
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
