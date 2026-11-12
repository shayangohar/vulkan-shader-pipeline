package net.chimera.render;

import net.chimera.shaderpack.MixinBytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reforged 0.3.5 keeps the scissor box as GL state and reapplies it where a pass begins
 * (Renderer.applyScissor), where 0.3.0 reset it to the framebuffer. Where Chimera begins a window in
 * place of a host pass, or hands the pass back after its own fullscreen draws, it reapplies the box
 * the game set rather than overwriting it.
 */
final class HostScissorTest {
    private static final String RENDERER = "net/vulkanmod/vulkan/Renderer";

    @Test
    void chimeraReappliesTheHostScissorBox() throws IOException {
        for (String[] seam : new String[][] {
                {"net/chimera/mixin/ChimeraFramebufferRenderPassMixin", "chimera$beginPackGeometry"},
                {"net/chimera/render/ChimeraMainPass", "restorePackRenderState"}}) {
            List<AbstractInsnNode> instructions =
                    MixinBytecode.instructionsThrough(MixinBytecode.nativeClass(seam[0]), seam[1]);
            assertTrue(calls(instructions, "applyScissor"), seam[1] + " reapplies the box");
            assertFalse(calls(instructions, "setScissor"), seam[1] + " leaves the box as the game set it");
        }
    }

    private static boolean calls(List<AbstractInsnNode> instructions, String name) {
        return instructions.stream().anyMatch(instruction -> instruction instanceof MethodInsnNode call
                && call.owner.equals(RENDERER) && call.name.equals(name));
    }
}
