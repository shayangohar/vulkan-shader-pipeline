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
