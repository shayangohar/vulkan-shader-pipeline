package net.chimera.mixin;

import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.vulkanmod.vulkan.device.Device;

/** Read-only access to the pinned VulkanMod physical-device limits. */
@Mixin(Device.class)
public interface ChimeraDeviceAccessor {
    @Accessor("properties")
    VkPhysicalDeviceProperties chimera$properties();
}
