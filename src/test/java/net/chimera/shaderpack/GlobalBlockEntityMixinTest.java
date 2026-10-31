package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ChimeraGlobalBlockEntityMixin keeps an off-screen block entity out of its section's list. The
 * add it wraps is the first after handleBlockEntity reads CompileResult.blockEntities, which must be
 * that list's own add: the global list's field is not read in between.
 */
final class GlobalBlockEntityMixinTest {
    private static final String COMPILE_RESULT = "net/vulkanmod/render/chunk/build/task/CompileResult";

    @Test
    void theWrappedAddIsTheSectionListsOwn() throws IOException {
        MixinBytecode.assertMixinRegistered("ChimeraGlobalBlockEntityMixin");
        var mixin = MixinBytecode.nativeClass("net/chimera/mixin/ChimeraGlobalBlockEntityMixin");
        assertTrue(MixinBytecode.injects(mixin, "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
                "handleBlockEntity", "Ljava/util/List;add(Ljava/lang/Object;)Z"));
        var task = MixinBytecode.nativeClass("net/vulkanmod/render/chunk/build/task/BuildTask");
        var method = task.methods.stream().filter(m -> m.name.equals("handleBlockEntity")).findFirst().orElseThrow();
        boolean afterSectionList = false;
        for (AbstractInsnNode insn : method.instructions) {
            if (insn instanceof FieldInsnNode field && field.owner.equals(COMPILE_RESULT)) {
                if (field.name.equals("blockEntities")) afterSectionList = true;
                if (field.name.equals("globalBlockEntities")) {
                    assertTrue(!afterSectionList, "the global list is read between the section list and its add");
                }
            }
            if (afterSectionList && insn instanceof MethodInsnNode call && call.name.equals("add")) return;
        }
        throw new AssertionError("handleBlockEntity never adds to CompileResult.blockEntities");
    }
}
