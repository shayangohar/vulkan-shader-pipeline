package net.chimera.render;

import net.chimera.shaderpack.MixinBytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** A pack session's flags and once-only logs end with it (pre-merge audit D4, PackSessionState). */
final class PackSessionStateTest {
    private static final String STATE = "net/chimera/render/PackSessionState";

    /** Cleanup replaces the record whole, so a field added to it cannot be left out of a reset list. */
    @Test
    void cleanupReplacesTheSessionWhole() throws IOException {
        var lifecycle = MixinBytecode.nativeClass("net/chimera/render/PackSessionLifecycle");
        boolean created = false;
        boolean stored = false;
        boolean fieldByField = false;
        for (AbstractInsnNode instruction : MixinBytecode.instructionsThrough(lifecycle, "cleanUpPackVariant")) {
            created |= instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW
                    && type.desc.equals(STATE);
            stored |= instruction instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD
                    && put.owner.equals("net/chimera/render/ChimeraMainPass") && put.name.equals("session");
            fieldByField |= instruction instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD
                    && put.owner.equals(STATE);
        }
        assertTrue(created && stored, "cleanUpPackVariant stores a new PackSessionState in the pass");
        assertTrue(!fieldByField, "no session field is reset one by one beside the new record");
    }

    /**
     * createResources configures the post targets before it loads the pipelines; the load clears a
     * rejection that configure set, so a target allocation that fails at load leaves the world
     * programs, entity bridge and pack shadows running without post (PackPostChain's notice).
     */
    @Test
    void theLoadClearsATargetRejectionItsOwnConfigureSet() throws IOException {
        var targets = MixinBytecode.nativeClass("net/chimera/render/MainPassTargets");
        String order = String.join(",", MixinBytecode.instructionsThrough(targets, "createResources").stream()
                .filter(instruction -> instruction instanceof MethodInsnNode)
                .map(instruction -> ((MethodInsnNode) instruction).name)
                .filter(name -> name.equals("configurePackPostTargets") || name.equals("loadPackPipelines"))
                .toList());
        assertTrue(order.startsWith("configurePackPostTargets,loadPackPipelines"), order);
        var pipelines = MixinBytecode.nativeClass("net/chimera/render/PackPipelineSet");
        AbstractInsnNode previous = null;
        boolean cleared = false;
        for (AbstractInsnNode instruction : MixinBytecode.instructionsThrough(pipelines, "resetSessionPipelines")) {
            cleared |= instruction instanceof FieldInsnNode put && put.getOpcode() == Opcodes.PUTFIELD
                    && put.owner.equals(STATE) && put.name.equals("packPostChainRejected")
                    && previous != null && previous.getOpcode() == Opcodes.ICONST_0;
            previous = instruction;
        }
        assertTrue(cleared, "resetSessionPipelines sets packPostChainRejected to false");
    }
}
