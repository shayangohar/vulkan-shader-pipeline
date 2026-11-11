package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
                "separateEntityDraws", "voxelizeLightBlocks", "rain.depth", "supportsColorCorrection")) {
            assertNull(PackPropertyKeys.unread(applied), applied);
        }
        // Source const names: Iris reads them only as const directives, so neither reader takes them here.
        for (String constOnly : List.of("colortex3Format", "colortex1Clear", "shadowMapResolution",
                "sunPathRotation")) {
            assertEquals("SETTING_UNKNOWN", PackPropertyKeys.unread(constOnly), constOnly);
        }
        for (String irisOnly : List.of("beacon.beam.depth", "backFace.solid", "dhShadow.enabled", "scale.composite",
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
                backFace.solid=true
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
        assertEquals(List.of("SETTING_NOT_APPLIED:backFace.solid"), settings);
    }

    /**
     * With supportsColorCorrection true or 1, Iris adds COLOR_SPACE_SRGB=0 through
     * COLOR_SPACE_ADOBE_RGB=4 to the shader defines (ShaderPack, ColorSpace), after it has
     * preprocessed shaders.properties. Kappa's ACES output switches on currentColorSpace with them.
     */
    @Test
    void colorCorrectionDefinesTheColorSpaces(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                void main() {
                #if defined COLOR_SPACE_SRGB && COLOR_SPACE_REC2020 == 3 && COLOR_SPACE_ADOBE_RGB == 4
                    gl_FragColor = vec4(0.25 * float(COLOR_SPACE_DISPLAY_P3));
                #else
                    gl_FragColor = vec4(0.75);
                #endif
                }
                """);
        for (String flag : List.of("true", "1", "false", "0")) {
            Files.writeString(shaders.resolve("shaders.properties"), """
                    supportsColorCorrection = %s
                    #ifdef COLOR_SPACE_SRGB
                    uniform.float.inProperties = 1.0
                    #endif
                    """.formatted(flag));
            PackPlan plan = PackProbe.analyze(root).plan();
            boolean on = flag.equals("true") || flag.equals("1");
            Map<String, String> defines = plan.settingsPreprocessorDefines();
            assertEquals(on ? "0" : null, defines.get("COLOR_SPACE_SRGB"), flag);
            assertEquals(on ? "2" : null, defines.get("COLOR_SPACE_DISPLAY_P3"), flag);
            String fragment = plan.program("composite").convertedFragment();
            assertEquals(on, fragment.contains("0.25") && !fragment.contains("0.75"), flag + ":\n" + fragment);
            // Kappa reads them as values too (case COLOR_SPACE_SRGB:): the use expands to the value.
            assertEquals(on, fragment.contains("0.25 * float(2)"), flag + ":\n" + fragment);
            assertEquals(-1, plan.runtimeSettings().indexOf("inProperties"),
                    "shaders.properties is preprocessed before the colour spaces are defined");
        }
    }
}
