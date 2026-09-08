package net.chimera.render;

import net.chimera.shaderpack.LegacyGlslConverter;

/** Vulkan-free checks for M8.2 coverage and scene-seed state. */
public final class M82ConformanceHarness {
    private M82ConformanceHarness() {}

    public static void run() {
        PackCoverageState state = new PackCoverageState();
        assertTrue(!state.canSeed(), "coverage must be inactive before a frame");
        state.beginFrame();
        assertTrue(state.canSeed(), "coverage must allow one seed per frame");
        state.beginPackWrite();
        state.commitPackWrite();
        assertTrue(state.writeCount() == 1, "successful pack write must be committed");
        state.commitSeed();
        assertTrue(state.seedApplied(), "successful seed must become visible");
        assertTrue(!state.canSeed(), "a frame must not seed twice");
        state.endFrame();

        state.beginFrame();
        state.beginPackWrite();
        state.abortPackWrite();
        assertTrue(state.writeCount() == 0, "aborted pack write must not commit");
        assertTrue(PackCoverageState.seedPreservesCoverage(0.5f,
                PackCoverageOwner.EMPTY_SENTINEL), "written coverage must be preserved");
        assertTrue(!PackCoverageState.seedPreservesCoverage(
                PackCoverageOwner.EMPTY_SENTINEL, PackCoverageOwner.EMPTY_SENTINEL),
                "empty coverage must be seedable");
        String converted = LegacyGlslConverter.withCoverageOutput(
                "#version 460\nlayout(location = 0) out vec4 color;\n"
                        + "void main() { color = vec4(1.0); }\n");
        assertTrue(converted.contains("layout(location = 1) out float chimeraCoverage"),
                "coverage output must be adjacent to pack color outputs");
        assertTrue(converted.contains("chimeraCoverage = gl_FragCoord.z"),
                "coverage output must be written by the fragment stage");
        state.endFrame();
        System.out.println("[chimera] M8.2 coverage harness: PASS");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        run();
    }
}
