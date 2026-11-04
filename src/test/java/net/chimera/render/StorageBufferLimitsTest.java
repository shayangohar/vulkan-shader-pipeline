package net.chimera.render;

import net.chimera.shaderpack.PackAdvancedResourcePlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;

/**
 * The AMD Radeon iGPU reports maxPerStageDescriptorStorageBuffers as 0xFFFFFFFF, no limit. Read
 * as a signed int it was -1, and RenderPearl's terrain, water, weather and hand were all refused
 * with STORAGE_BUFFER_STAGE_LIMIT, so the pack's world drew through the identity fallback.
 */
final class StorageBufferLimitsTest {
    private static PackAdvancedResourcePlan.StorageBufferBinding buffer(int index, int stages) {
        return new PackAdvancedResourcePlan.StorageBufferBinding("gbuffers_terrain", "LightList", "", index, index,
                index, stages, 16, PackAdvancedResourcePlan.Status.SUPPORTED, List.of());
    }

    @Test
    void limitsAreUnsignedCounts() {
        List<PackAdvancedResourcePlan.StorageBufferBinding> bindings = List.of(
                buffer(0, VK_SHADER_STAGE_VERTEX_BIT),
                buffer(1, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT),
                buffer(2, VK_SHADER_STAGE_FRAGMENT_BIT));
        // AMD's proprietary driver: 8 dynamic storage buffers per set, no per-stage limit.
        assertNull(PackStorageBufferOwner.limitFailure("gbuffers_terrain", bindings, 8, 0xFFFFFFFF));
        // NVIDIA's limits.
        assertNull(PackStorageBufferOwner.limitFailure("gbuffers_terrain", bindings, 16, 0x100000));
        // A device whose limits the program exceeds still refuses it.
        assertEquals("STORAGE_BUFFER_STAGE_LIMIT:gbuffers_terrain",
                PackStorageBufferOwner.limitFailure("gbuffers_terrain", bindings, 8, 1));
        assertEquals("STORAGE_BUFFER_DESCRIPTOR_LIMIT:gbuffers_terrain",
                PackStorageBufferOwner.limitFailure("gbuffers_terrain", bindings, 2, 0xFFFFFFFF));
    }
}
