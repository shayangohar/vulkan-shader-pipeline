package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris PackShadowDirectives' sampling settings. Chimera drew every pack's shadowcolor0 and
 * shadowcolor1 as RGBA8 cleared to white: Kappa's RGBA16, Spring's RGBA16F and BSL's grey clear
 * were all lost.
 */
final class ShadowTargetDirectivesTest {
    @Test
    void irisDefaultsAreRgbaClearedToWhiteLinearWithoutMips() {
        ShadowTargetDirectives defaults = ShadowTargetDirectives.defaults();
        ShadowTargetDirectives.ColorTarget target = defaults.color(5);
        assertEquals("RGBA8", target.format().token());
        assertTrue(target.clear());
        assertArrayEquals(new float[] {1.0F, 1.0F, 1.0F, 1.0F}, target.clearColor());
        assertFalse(target.mipmap() || target.nearestFiltering());
        assertFalse(defaults.depth(1).hardwareFiltering() || defaults.depth(1).mipmap() || defaults.depth(1).nearest());
    }

    @Test
    void numberedDirectivesSetOneTargetAndBaseDirectivesSetAll() {
        ShadowTargetDirectives directives = ShadowTargetDirectives.parse(List.of("""
                const int shadowcolor0Format = RGBA16F;
                const bool shadowcolor0Clear = false;
                const vec4 shadowcolor1ClearColor = vec4(0.25, 0.25, 0.25, 1.0);
                const bool shadowColor1MinMagNearest = true;
                const int shadowcolor2Format = R32UI;
                const bool generateShadowColorMipmap = true;
                const bool shadowcolor3Mipmap = false;
                const bool shadowHardwareFiltering = true;
                const bool shadowHardwareFiltering1 = false;
                const bool shadowtexNearest = true;
                const bool shadow1MinMagNearest = true;
                const bool shadowtex1Mipmap = true;
                """));
        assertEquals("RGBA16F", directives.color(0).format().token());
        assertFalse(directives.color(0).clear());
        assertArrayEquals(new float[] {0.25F, 0.25F, 0.25F, 1.0F}, directives.color(1).clearColor());
        assertTrue(directives.color(1).nearestFiltering());
        assertTrue(directives.color(2).nearestFiltering(), "an integer format never filters linearly");
        assertTrue(directives.color(7).mipmap() && !directives.color(3).mipmap());
        assertTrue(directives.depth(0).hardwareFiltering() && !directives.depth(1).hardwareFiltering());
        assertTrue(directives.depth(0).nearest() && directives.depth(1).nearest());
        assertTrue(directives.depth(1).mipmap() && !directives.depth(0).mipmap());
        // Chimera samples the base level of a shadow depth Iris mipmaps (KNOW-779), and says so.
        assertEquals(List.of("SHADOW_DEPTH_MIPMAP_UNSUPPORTED:shadowtex1"), directives.deviations());
    }

    /** Iris reads each directive by its declared type and ignores a value it cannot read. */
    @Test
    void wrongTypesAndUnknownValuesAreIgnored() {
        ShadowTargetDirectives directives = ShadowTargetDirectives.parse(List.of("""
                const int shadowHardwareFiltering = 1;
                const bool shadowcolor0Nearest = yes;
                const int shadowcolor0Format = NOT_A_FORMAT;
                const vec4 shadowcolor1ClearColor = vec4(0.5);
                const bool shadowColor0Clear = false;
                const int shadowcolor1Format = RGB565;
                """));
        assertFalse(directives.depth(0).hardwareFiltering());
        assertFalse(directives.color(0).nearestFiltering());
        assertEquals("RGBA8", directives.color(0).format().token());
        assertTrue(directives.color(0).clear(), "only shadowcolorNClear names the clear");
        assertArrayEquals(new float[] {1.0F, 1.0F, 1.0F, 1.0F}, directives.color(1).clearColor());
        assertEquals(List.of("SHADOW_TARGET_CLEAR_COLOR_IGNORED:shadowcolor1",
                "SHADOW_TARGET_FORMAT_APPROXIMATED:shadowcolor1=RGB565->RGBA8",
                "SHADOW_TARGET_FORMAT_IGNORED:shadowcolor0=NOT_A_FORMAT"), directives.deviations());
    }

    /**
     * With HIGHER_SHADOWCOLOR the pass renders every shadowcolor target up to the highest a
     * program reads or a shadow program writes; without it, two, and Iris leaves shadowcolor2-7
     * unbound: a pass reads unit 0 (colortex0) and a world program cannot run.
     */
    @Test
    void higherShadowColorAddsTheTargetsProgramsUse(@TempDir Path root) throws IOException {
        Path shaders = writeHigherShadowColorPack(root, "iris.features.required=HIGHER_SHADOWCOLOR\n");
        PackProbe.Analysis higher = PackProbe.analyze(root);
        assertEquals(6, higher.plan().program("shadow").shadowAttachmentFormats().size());
        assertTrue(higher.plan().program("shadow").executable(), higher.plan().program("shadow").deviations()
                .toString());
        PackProgramPlan composite = higher.plan().program("composite");
        assertTrue(composite.executable()
                && !composite.deviations().contains("SAMPLER_UNIT0_COLORTEX0:shadowcolor5")
                && !composite.deviations().contains("SAMPLER_NOT_MAPPED:shadowcolor5"),
                composite.deviations().toString());
        assertTrue(higher.plan().program("gbuffers_terrain").executable());

        Files.writeString(shaders.resolve("shaders.properties"), "");
        PackProbe.Analysis legacy = PackProbe.analyze(root);
        assertEquals(2, legacy.plan().program("shadow").shadowAttachmentFormats().size());
        PackProgramPlan shadow = legacy.plan().program("shadow");
        assertTrue(shadow.executable() && shadow.deviations().contains("SHADOW_DRAWBUFFERS_RESET:3"),
                shadow.deviations().toString());
        assertTrue(legacy.plan().program("composite").deviations().contains("SAMPLER_UNIT0_COLORTEX0:shadowcolor5"));
        PackProgramPlan terrain = legacy.plan().program("gbuffers_terrain");
        assertTrue(terrain.deviations().contains("SAMPLER_NOT_MAPPED:shadowcolor2") && !terrain.executable());
    }

    /**
     * A shadow program naming a target the pass does not have draws to {0, 1} instead, as Iris's
     * ShadowRenderTargets.createColorFramebuffer does: its first two outputs write shadowcolor0
     * and shadowcolor1, and the rest are discarded.
     */
    @Test
    void aShadowTargetPastThePassDrawsToTheFirstTwo(@TempDir Path root) throws IOException {
        writeHigherShadowColorPack(root, "iris.features.required=HIGHER_SHADOWCOLOR\n");
        Files.writeString(root.resolve("shaders/shadow.fsh"), """
                #version 120
                /* RENDERTARGETS: 9,4,2 */
                varying vec2 uv;
                void main() {
                    gl_FragData[0] = vec4(uv, 0.0, 1.0);
                    gl_FragData[1] = vec4(1.0);
                    gl_FragData[2] = vec4(0.5);
                }
                """);
        PackProgramPlan shadow = PackProbe.analyze(root).plan().program("shadow");
        assertTrue(shadow.executable() && shadow.deviations().contains("SHADOW_DRAWBUFFERS_RESET:9"),
                shadow.deviations().toString());
        assertTrue(shadow.convertedFragment().contains("chimeraShadowColor1")
                && !shadow.convertedFragment().contains("chimeraShadowColor4"), shadow.convertedFragment());
    }

    /**
     * With SEPARATE_HARDWARE_SAMPLERS, shadowtexNHW reads its depth through the comparison sampler,
     * and only for a depth with shadowHardwareFiltering (IrisSamplers.addShadowSamplers).
     */
    @Test
    void hardwareShadowSamplersNeedTheFeatureAndTheirFiltering(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "iris.features.required=SEPARATE_HARDWARE_SAMPLERS\n");
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                const bool shadowHardwareFiltering0 = true;
                uniform sampler2DShadow shadowtex0HW;
                uniform sampler2DShadow shadowtex1HW;
                varying vec2 uv;
                void main() {
                    float lit = shadow2D(shadowtex0HW, vec3(uv, 0.5)).x;
                    gl_FragData[0] = vec4(lit + shadow2D(shadowtex1HW, vec3(uv, 0.5)).x);
                }
                """);
        PackProgramPlan separate = PackProbe.analyze(root).plan().program("composite");
        assertFalse(separate.deviations().contains("SAMPLER_NOT_MAPPED:shadowtex0HW"),
                separate.deviations().toString());
        assertTrue(separate.deviations().contains("SAMPLER_NOT_MAPPED:shadowtex1HW"), "no filtering for shadowtex1");
        assertTrue(separate.interfacePlan().samplers().stream()
                .anyMatch(sampler -> sampler.name().equals("shadowtex0HW")
                && sampler.slot() == SelectorNamespace.SHADOW_TEX0_HW_SLOT));

        Files.writeString(shaders.resolve("shaders.properties"), "");
        PackProgramPlan legacy = PackProbe.analyze(root).plan().program("composite");
        assertTrue(legacy.deviations().contains("SAMPLER_NOT_MAPPED:shadowtex0HW") && !legacy.executable(),
                legacy.deviations().toString());
    }

    /** GL leaves a sampler nothing reads inactive, so Escheridia's unread shadowtex0HW needs no binding. */
    @Test
    void anUnreadUnboundHardwareSamplerIsInactive(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_entities.vsh"), """
                #version 120
                varying vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_entities.fsh"), """
                #version 120
                uniform sampler2D texture;
                uniform sampler2DShadow shadowtex0HW;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(texture, uv); }
                """);
        PackProgramPlan entities = PackProbe.analyze(root).plan().program("gbuffers_entities");
        assertTrue(entities.executable() && !entities.deviations().contains("SAMPLER_NOT_MAPPED:shadowtex0HW"),
                entities.deviations().toString());
    }

    /** A pass's unbound sampler2D shadowtexNHW reads texture unit 0, colortex0, as Iris leaves it. */
    @Test
    void anUnfilteredHardwareSampler2DReadsUnitZero(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "iris.features.required=SEPARATE_HARDWARE_SAMPLERS\n");
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform sampler2D shadowtex1HW;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(shadowtex1HW, uv); }
                """);
        PackProgramPlan composite = PackProbe.analyze(root).plan().program("composite");
        assertTrue(composite.executable() && composite.deviations().contains("SAMPLER_UNIT0_COLORTEX0:shadowtex1HW"),
                composite.deviations().toString());
    }

    private static Path writeHigherShadowColorPack(Path root, String properties) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), properties);
        String vertex = """
                #version 120
                varying vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """;
        Files.writeString(shaders.resolve("shadow.vsh"), vertex);
        Files.writeString(shaders.resolve("shadow.fsh"), """
                #version 120
                /* DRAWBUFFERS:03 */
                varying vec2 uv;
                void main() { gl_FragData[0] = vec4(uv, 0.0, 1.0); gl_FragData[1] = vec4(1.0); }
                """);
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), vertex);
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), """
                #version 120
                uniform sampler2D shadowcolor2;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(shadowcolor2, uv); }
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform sampler2D shadowcolor5;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(shadowcolor5, uv); }
                """);
        return shaders;
    }

    /** The directives come from fragment sources, as Iris's ProgramSet.locateDirectives reads them. */
    @Test
    void shadowProgramsDeclareThePacksShadowcolorFormats(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shadow.vsh"), """
                #version 120
                const int shadowcolor1Format = R8;
                varying vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("shadow.fsh"), """
                #version 120
                const int shadowcolor0Format = RGBA16F;
                uniform sampler2D tex;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(tex, uv); }
                """);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        assertEquals(List.of(97, 37), analysis.plan().program("shadow").shadowAttachmentFormats(),
                "RGBA16F, then the RGBA8 a vertex directive does not change");
    }
}
