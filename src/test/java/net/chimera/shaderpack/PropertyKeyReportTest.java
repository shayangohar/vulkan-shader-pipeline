package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * shaders.properties keys as the report names them: a key Iris 1.21.1's ShaderProperties reads
 * and Chimera does not apply is SETTING_NOT_APPLIED; one Chimera applies, or one Iris does not
 * read either, is not reported. Keys inside a branch the pack's conditionals skip are not read.
 */
final class PropertyKeyReportTest {
    @Test
    void keysAreClassifiedAgainstIris() {
        for (String applied : List.of("clouds", "blend.composite", "screen.MAIN", "screen", "sliders",
                "profile.HIGH", "vignette", "shadowTranslucent", "particles.ordering", "image.voxels",
                "size.buffer.colortex1", "uniform.float.x", "shadow.culling", "shadow.enabled",
                "separateEntityDraws", "voxelizeLightBlocks")) {
            assertNull(PackPropertyKeys.unread(applied), applied);
        }
        // Source const names: Iris reads them only as const directives, so neither reader takes them here.
        for (String constOnly : List.of("colortex3Format", "colortex1Clear", "shadowMapResolution",
                "sunPathRotation")) {
            assertEquals("SETTING_UNKNOWN", PackPropertyKeys.unread(constOnly), constOnly);
        }
        for (String irisOnly : List.of("rain.depth", "backFace.solid", "dhShadow.enabled", "scale.composite",
                "weather")) {
            assertEquals("SETTING_UNSUPPORTED", PackPropertyKeys.unread(irisOnly), irisOnly);
        }
        assertEquals("SETTING_UNKNOWN", PackPropertyKeys.unread("customImage0"));
    }

    /**
     * PropertiesPreprocessor leaves a comment line empty, and an empty line ends a backslash
     * continuation in Properties.load; a blank line is dropped first and ends nothing. A '#' with a
     * space before the directive's name is a comment. Bliss's screen list ends at the commented-out
     * one under it.
     */
    @Test
    void aCommentEndsAContinuationAndASpacedDirectiveIsAComment(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                screen = A \\

                    B \\
                # screen = \\
                #   C \\
                screen.A.columns=1
                # if 0
                vignette=false
                # endif
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(colortex0, uv); }
                """);
        var values = PackProbe.analyze(root).settings().propertyValues();
        assertEquals("A B", values.get("screen").replaceAll("\\s+", " ").trim());
        assertEquals("1", values.get("screen.A.columns"));
        assertEquals("false", values.get("vignette"));
    }

    @Test
    void theReportNamesOnlyKeysIrisReadsAndChimeraDoesNotApply(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), """
                rain.depth=true
                vignette=false
                blend.composite=off
                customImage0=x.png
                #if 0
                beacon.beam.depth=true
                #endif
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(colortex0, uv); }
                """);
        List<String> settings = PackProbe.analyze(root).report().deviations().stream()
                .filter(deviation -> deviation.startsWith("SETTING_")).toList();
        assertEquals(List.of("SETTING_NOT_APPLIED:rain.depth"), settings);
    }
}
