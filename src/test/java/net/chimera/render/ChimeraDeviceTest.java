package net.chimera.render;

import net.chimera.shaderpack.PackDevice;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The features the device is created with are the ones the offline profiles and the guard name. */
final class ChimeraDeviceTest {
    /** Each table, with VulkanMod's own flag, lists the profile's names in its order. */
    @Test
    void theTablesNameThePackDeviceFeatures() {
        List<String> core = new ArrayList<>(ChimeraDevice.CORE_FEATURES.stream().map(ChimeraDevice.Feature::name)
                .toList());
        core.add(core.indexOf("geometryShader") + 1, "tessellationShader");
        assertEquals(PackDevice.CORE_FEATURES, core);
        List<String> vulkan11 = new ArrayList<>(ChimeraDevice.VULKAN11_FEATURES.stream()
                .map(ChimeraDevice.Feature::name).toList());
        vulkan11.add("shaderDrawParameters");
        assertEquals(PackDevice.VULKAN11_FEATURES, vulkan11);
        assertEquals(PackDevice.VULKAN12_FEATURES, ChimeraDevice.VULKAN12_FEATURES.stream()
                .map(ChimeraDevice.Feature::name).toList());
    }

    /** Each entry reads and writes its own member. */
    @Test
    void eachEntryWritesTheMemberItReads() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (ChimeraDevice.Feature<VkPhysicalDeviceFeatures> feature : ChimeraDevice.CORE_FEATURES) {
                VkPhysicalDeviceFeatures features = VkPhysicalDeviceFeatures.calloc(stack);
                feature.write().accept(features, true);
                assertTrue(feature.read().test(features), feature.name());
                long set = ChimeraDevice.CORE_FEATURES.stream().filter(other -> other.read().test(features)).count();
                assertEquals(1, set, feature.name() + " sets another member too");
            }
        }
    }
}
