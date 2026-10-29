package net.chimera.render.shader;

import net.chimera.shaderpack.PackDescriptorLayout;
import net.vulkanmod.vulkan.shader.descriptor.ImageDescriptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HostPipelineLayoutImagesTest {
    private static final PackDescriptorLayout LAYOUT = PackDescriptorLayout.EMPTY.withImages(List.of(
            new PackDescriptorLayout.ImageSlot(0, "sampler2D", "Sampler0", 0),
            new PackDescriptorLayout.ImageSlot(1, "sampler2D", "Sampler1", 1),
            new PackDescriptorLayout.ImageSlot(2, "sampler2D", "Sampler2", 2),
            new PackDescriptorLayout.ImageSlot(3, "sampler2D", "Sampler5", 5),
            new PackDescriptorLayout.ImageSlot(4, "sampler2D", "Sampler15", 15)));

    @Test
    void packImagesOtherThanAlbedoAndLightmapSkipRenderSystem() {
        // The overlay (1), shadowtex0 (5) and shadowtex1 (15) are Chimera's to bind; an eyes draw leaves unit 1 empty.
        assertEquals(List.of(true, false, true, false, false), vanillaBinding(HostPipelineLayout.images(LAYOUT, true)));
    }

    @Test
    void chimerasOwnShadersReadEveryHostUnit() {
        assertEquals(List.of(true, true, true, true, true), vanillaBinding(HostPipelineLayout.images(LAYOUT, false)));
    }

    private static List<Boolean> vanillaBinding(List<ImageDescriptor> images) {
        return images.stream().map(ImageDescriptor::usesVanillaTextureBinding).toList();
    }
}
