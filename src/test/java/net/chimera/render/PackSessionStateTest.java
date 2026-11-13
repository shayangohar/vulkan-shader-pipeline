package net.chimera.render;

import net.chimera.shaderpack.MixinBytecode;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
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
}
