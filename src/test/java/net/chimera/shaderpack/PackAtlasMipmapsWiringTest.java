package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The block atlas takes Iris's mip levels only while a pack's terrain directives are installed
 * (PackAtlasMipmaps, TASK-1000): every lifecycle point records the wanted state, the frame boundary
 * reconciles it after any pack change, and a restitched atlas is reported.
 */
final class PackAtlasMipmapsWiringTest {
    private static final String OWNER = "net/chimera/render/PackAtlasMipmaps";

    @Test
    void lifecyclePointsRecordWhetherAPackIsInstalled() throws IOException {
        var lifecycle = MixinBytecode.nativeClass("net/chimera/render/PackSessionLifecycle");
        assertEquals(List.of(false), installedStates(lifecycle, "cleanUpPackVariant"),
                "a pack variant's teardown leaves no pack installed");
        assertEquals(List.of(false, true), installedStates(lifecycle, "loadPackConfig"),
                "loading leaves no pack without one, and installs one with its terrain directives");
    }

    /**
     * When a pack load and its rollback both fail, Chimera falls back to the host renderer. That
     * path reset none of the pack's host directives, so vanilla kept the pack's atlas mips and its
     * block.properties layers (glass drawn translucent, through ItemBlockRenderTypes).
     */
    @Test
    void aFailedRollbackFallsBackWithThePacksHostDirectivesReset() throws IOException {
        var lifecycle = MixinBytecode.nativeClass("net/chimera/render/PackSessionLifecycle");
        int fallbacks = 0;
        for (var method : lifecycle.methods) {
            boolean fellBack = false;
            boolean atlasReset = false;
            boolean materialMapReset = false;
            for (AbstractInsnNode instruction : MixinBytecode.instructionsThrough(lifecycle, method.name)) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                if (call.name.equals("fallbackToHostRenderer")) {
                    if (fellBack) assertTrue(atlasReset && materialMapReset, method.name);
                    fellBack = true;
                    fallbacks++;
                    atlasReset = false;
                    materialMapReset = false;
                }
                if (fellBack && call.owner.equals(OWNER) && call.name.equals("setPackInstalled")) {
                    atlasReset = call.getPrevious().getOpcode() == Opcodes.ICONST_0;
                }
                if (fellBack && call.name.equals("setMaterialResolver")) materialMapReset = true;
            }
            if (fellBack) {
                assertTrue(atlasReset, method.name + ": the fallback leaves the pack atlas mips wanted");
                assertTrue(materialMapReset, method.name + ": the fallback leaves the pack's block layers on");
            }
        }
        assertTrue(fallbacks >= 2, "the pack change and dimension variant rollbacks both fall back");
    }

    @Test
    void theFrameBoundaryReconcilesAfterAnyPackChange() throws IOException {
        var renderer = MixinBytecode.nativeClass("net/chimera/render/ChimeraRenderer");
        boolean packChange = false;
        boolean reconciledAfter = false;
        for (AbstractInsnNode instruction : MixinBytecode.instructionsThrough(renderer, "beforeMainCommandBuffer")) {
            if (!(instruction instanceof MethodInsnNode call)) continue;
            if (call.name.equals("rebuildingOnce")) packChange = true;
            if (packChange && call.owner.equals(OWNER) && call.name.equals("reconcile")) reconciledAfter = true;
        }
        assertTrue(reconciledAfter, "the atlas mip levels must be reconciled after the frame's pack change");
    }

    @Test
    void aRestitchedAtlasIsReported() throws IOException {
        var mixin = MixinBytecode.nativeClass("net/chimera/mixin/ChimeraAtlasMaterialMixin");
        boolean noted = false;
        for (AbstractInsnNode instruction : MixinBytecode.instructionsThrough(mixin, "chimera$noteAtlasUpload")) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(OWNER)
                    && call.name.equals("noteAtlasUpload")) {
                noted = true;
            }
        }
        assertTrue(noted, "TextureAtlas.upload must report the restitched atlas");
    }

    /** The constant passed to each PackAtlasMipmaps.setPackInstalled call, in bytecode order. */
    private static List<Boolean> installedStates(ClassNode owner, String method) {
        List<Boolean> states = new ArrayList<>();
        for (AbstractInsnNode instruction : MixinBytecode.instructionsThrough(owner, method)) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(OWNER)
                    && call.name.equals("setPackInstalled")) {
                states.add(call.getPrevious().getOpcode() == Opcodes.ICONST_1);
            }
        }
        return states;
    }
}
