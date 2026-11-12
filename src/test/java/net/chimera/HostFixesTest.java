package net.chimera;

import net.chimera.shaderpack.MixinBytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Defects of VulkanMod Reforged 0.3.0 that 0.3.5 fixed, where Chimera dropped its own workaround
 * (KNOW-1152). A host that loses the fix brings the defect back, so each is pinned to the host
 * code that carries it.
 */
final class HostFixesTest {
    /**
     * A render target blits to the screen by the row order its image recorded: the GL-framebuffer
     * blit for a target drawn bottom-up, such as the entity outline (KNOW-861). Chimera's 0.3.0
     * workaround, a blit under an inverted viewport, would now flip it a second time.
     */
    @Test
    void aRenderTargetBlitsByItsRecordedRowOrder() throws IOException {
        List<AbstractInsnNode> copy = hostMethod("net/vulkanmod/render/RenderTargetBlit$Call", "copy");
        assertTrue(calls(copy, "net/vulkanmod/vulkan/texture/VulkanImage", "isRowsBottomUp")
                && calls(copy, "net/vulkanmod/vulkan/util/DrawUtil", "blitToScreen", "(Z)V"));
    }

    private static List<AbstractInsnNode> hostMethod(String owner, String method) throws IOException {
        return MixinBytecode.instructionsThrough(MixinBytecode.nativeClass(owner), method);
    }

    private static boolean calls(List<AbstractInsnNode> instructions, String owner, String name) {
        return instructions.stream().anyMatch(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name));
    }

    private static boolean calls(List<AbstractInsnNode> instructions, String owner, String name, String desc) {
        return instructions.stream().anyMatch(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc));
    }
}
