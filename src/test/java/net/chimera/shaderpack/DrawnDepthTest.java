package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris's depthtex0 is the depth drawn so far. A program that runs while the world still draws
 * reads a capture of the world drawn before its group of draws; superDuperVanilla's end portal
 * read last frame's, which held the portal itself, and glowed over its whole surface.
 */
final class DrawnDepthTest {
    @Test
    void aManifestNamesTheDepthSnapshotsItReads() {
        var colour = new ProgramImageBindingManifest.Entry("colortex0", 0, 1, 63,
                ProgramImageBindingManifest.Kind.COLOR_TARGET, "colortex0", 0, 0, "colortex0");
        var depth = new ProgramImageBindingManifest.Entry("depthtex0", 6, 1, 63,
                ProgramImageBindingManifest.Kind.DEPTH_TARGET, "depthtex0", 0, 6, "depthtex0");
        var reader = new ProgramImageBindingManifest(List.of(colour, depth));
        assertTrue(reader.readsDepth("depthtex0"), "a depthtex0 entry is a depthtex0 read");
        assertFalse(reader.readsDepth("depthtex1"), "depthtex0 is not depthtex1");
        assertFalse(new ProgramImageBindingManifest(List.of(colour)).readsDepth("colortex0"),
                "a colour target is no depth snapshot");
    }

    /** The prepare seam captures depthtex0; each group of world draws makes that capture stale. */
    @Test
    void depthtex0IsCapturedAgainAfterEachGroupOfWorldDraws() throws IOException {
        assertCalls("net/chimera/render/PackImageBindings", "prepareProgramImages",
                "needsDrawnDepth", "captureDrawnDepth");
        assertCalls("net/chimera/render/ChimeraMainPass", "openLevelSegment", "markWorldDrawn");
        assertCalls("net/chimera/render/PackSceneCoverage", "endPackCoverageWindow", "markWorldDrawn");
        assertCalls("net/chimera/render/PackFamilyWindows", "drawFamilyBatches", "markWorldDrawn");
        assertCalls("net/chimera/mixin/ChimeraLevelRendererMixin", "chimera$particleLanes", "markWorldDrawn");
        // A post pass that runs before the world ends, prepare among them, needs no earlier capture.
        assertCalls("net/chimera/render/PackPostChain", "packDepthInputsAvailable", "drawnDepthCapturable");
    }

    private static void assertCalls(String owner, String method, String... callees) throws IOException {
        var instructions = MixinBytecode.instructionsThrough(MixinBytecode.nativeClass(owner), method);
        for (String callee : callees) {
            boolean calls = instructions.stream()
                    .anyMatch(instruction -> instruction instanceof MethodInsnNode call && call.name.equals(callee));
            assertTrue(calls, owner + "." + method + " must call " + callee);
        }
    }
}
