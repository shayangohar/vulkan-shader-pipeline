package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The converted-output gate names each changed shader, and lets source drift through when only SPIR-V must hold. */
final class ConvertedOutputSnapshotTest {
    @Test
    void namesEveryChangeAndHonoursSpirvOnly(@TempDir Path fixtures) {
        System.setProperty("chimera.fixtureRoot", fixtures.toString());
        ConvertedOutputSnapshot recorded = new ConvertedOutputSnapshot("selftest.zip");
        recorded.record("final.fsh", "void main() {}", "aaaa");
        recorded.record("final.vsh", "void main() { gl_Position = vec4(0.0); }", "bbbb");
        recorded.record("composite.fsh", "void main() {}", "cccc");
        assertEquals(List.of(), recorded.compareWithBaseline(true, false));
        assertTrue(Files.isRegularFile(fixtures.resolve("baselines/converted/selftest.zip.json")));

        ConvertedOutputSnapshot sourceOnly = new ConvertedOutputSnapshot("selftest.zip");
        sourceOnly.record("final.fsh", "#define X\nvoid main() {}", "aaaa");
        sourceOnly.record("final.vsh", "void main() { gl_Position = vec4(0.0); }", "bbbb");
        sourceOnly.record("composite.fsh", "void main() {}", "cccc");
        assertTrue(sourceOnly.compareWithBaseline(false, false).get(0).contains("source final.fsh"),
                "a source change fails the byte-identical gate");
        assertEquals(List.of(), sourceOnly.compareWithBaseline(false, true), "the same SPIR-V passes spirvOnly");

        ConvertedOutputSnapshot changed = new ConvertedOutputSnapshot("selftest.zip");
        changed.record("final.fsh", "void main() {}", "dddd");
        changed.record("final.vsh", "void main() { gl_Position = vec4(0.0); }", "bbbb");
        changed.record("deferred.fsh", "void main() {}", "eeee");
        String failure = changed.compareWithBaseline(false, true).get(0);
        assertTrue(failure.contains("3 shaders") && failure.contains("removed composite.fsh")
                && failure.contains("new deferred.fsh") && failure.contains("spirv final.fsh"), failure);
    }
}
