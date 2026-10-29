package net.chimera.render;

import net.chimera.shaderpack.PackBlendPlan;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_UNORM;

/** Which blend each attachment of a window pipeline draws with. */
final class WindowBlendAttachmentsTest {
    private static final int ALL_CHANNELS = 0xF;
    private static final int[] KAPPA_WATER_FORMATS = {VK_FORMAT_R16G16B16A16_UNORM, VK_FORMAT_R16G16B16A16_UNORM,
            VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R16G16B16A16_UNORM};

    /**
     * Kappa's gbuffers_water draws colortex1-4 with blend.gbuffers_water.colortex1, 2 and 4 off, and
     * colortex3 under the translucent render type's own blend, as in Iris. The directive on
     * attachment 0 must not reach colortex3, or stained glass overwrites the target and draws black.
     */
    @Test
    void anAttachmentWithoutADirectiveKeepsTheHostBlend() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineColorBlendAttachmentState.Buffer attachments = hostTranslucent(stack, 4);
            PackBlendPlan.Mode[] blends = {PackBlendPlan.Mode.OFF, PackBlendPlan.Mode.OFF, null,
                    PackBlendPlan.Mode.OFF};
            WindowBlendAttachments.fill(attachments, blends, 0b1111, KAPPA_WATER_FORMATS);
            for (int index : new int[] {0, 1, 3}) {
                assertFalse(attachments.get(index).blendEnable(), "colortex" + (index + 1) + " is set off");
            }
            VkPipelineColorBlendAttachmentState glass = attachments.get(2);
            assertTrue(glass.blendEnable(), "colortex3 lost the translucent blend");
            assertEquals(VK_BLEND_FACTOR_SRC_ALPHA, glass.srcColorBlendFactor());
            assertEquals(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, glass.dstColorBlendFactor());
            assertEquals(VK_BLEND_FACTOR_ONE, glass.srcAlphaBlendFactor());
            assertEquals(ALL_CHANNELS, glass.colorWriteMask());
        }
    }

    /** An unwritten attachment 0 gets no writes; the attachments after it keep the host's mask. */
    @Test
    void anUnwrittenFirstAttachmentDoesNotMaskTheOthers() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineColorBlendAttachmentState.Buffer attachments = hostTranslucent(stack, 4);
            WindowBlendAttachments.fill(attachments, null, 0b1110, KAPPA_WATER_FORMATS);
            assertEquals(0, attachments.get(0).colorWriteMask());
            for (int index = 1; index < 4; index++) {
                assertEquals(ALL_CHANNELS, attachments.get(index).colorWriteMask(), "attachment " + index);
                assertTrue(attachments.get(index).blendEnable(), "attachment " + index);
            }
        }
    }

    /** VulkanMod's state for a translucent draw, written into attachment 0 only. */
    private static VkPipelineColorBlendAttachmentState.Buffer hostTranslucent(MemoryStack stack, int count) {
        VkPipelineColorBlendAttachmentState.Buffer attachments =
                VkPipelineColorBlendAttachmentState.calloc(count, stack);
        attachments.get(0).colorWriteMask(ALL_CHANNELS).blendEnable(true)
                .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                .alphaBlendOp(VK_BLEND_OP_ADD);
        return attachments;
    }
}
