package net.chimera.mixin;

import net.vulkanmod.vulkan.device.Device;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only access to the pinned VulkanMod physical-device limits. */
@Mixin(Device.class)
public interface ChimeraDeviceAccessor {
    @Accessor("properties")
    VkPhysicalDeviceProperties chimera$properties();
}
