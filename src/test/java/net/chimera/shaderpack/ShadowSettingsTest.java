package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pack's shadow resolution, distance and depth range, as Iris's PackShadowDirectives reads them. */
final class ShadowSettingsTest {
    /**
     * Iris clamps no shadow setting (PackShadowDirectives): only a resolution that sizes no image
     * or a distance that is not finite takes Iris's default. shadowMapFov's perspective map is
     * Chimera's gap.
     */
    @Test
    void shadowSettingValidation(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        PackConfig.ShadowSettings settings = shadowSettings(shaders, """
                const int shadowMapResolution = 0;
                const float shadowDistance = Infinity;
                const float shadowMapFov = 90.0;
                const float shadowIntervalSize = 128.0;
                """);
        assertEquals(PackConfig.IRIS_SHADOW_MAP_RESOLUTION, settings.resolution(), "a resolution sizes no image");
        assertEquals(PackConfig.IRIS_SHADOW_DISTANCE, settings.distance(), "an infinite distance");
        assertEquals(List.of("SHADOW_SETTING_DEFAULTED:shadowDistance", "SHADOW_SETTING_DEFAULTED:shadowMapResolution",
                "SHADOW_SETTING_UNSUPPORTED:shadowMapFov"), settings.deviations());
        assertEquals(128.0F, settings.intervalSize(), "Iris takes any finite interval");

        PackConfig.ShadowSettings large = shadowSettings(shaders, """
                const int shadowMapResolution = 16384;
                const float shadowDistance = 8192.0;
                """);
        assertEquals(16384, large.resolution(), "packs offer 8192 and 16384; the device limit applies at creation");
        assertEquals(8192.0F, large.distance());
    }

    /**
     * A pack without directives gets Iris's 1024 and 160 and its depth range -100.05..156
     * (ShadowMatrices); shadowNearPlane and shadowFarPlane replace it, and -1 reaches the render
     * distance (ShadowRenderer, MatrixUniforms).
     */
    @Test
    void shadowDepthRangeFollowsIris(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        PackConfig.ShadowSettings none = shadowSettings(shaders, "void main() {}");
        assertEquals(1024, none.resolution());
        assertEquals(160.0F, none.distance());
        assertEquals(-100.05F, none.nearPlane(12));
        assertEquals(156.0F, none.farPlane(12));
        PackConfig.ShadowSettings planes = shadowSettings(shaders, """
                const float shadowNearPlane = 0.05;
                const float shadowFarPlane = 2048.0;
                """);
        assertEquals(0.05F, planes.nearPlane(12));
        assertEquals(2048.0F, planes.farPlane(12));
        PackConfig.ShadowSettings renderDistance = shadowSettings(shaders, """
                const float shadowNearPlane = -1.0;
                const float shadowFarPlane= -1. ;
                """);
        assertEquals(-192.0F, renderDistance.nearPlane(12));
        assertEquals(192.0F, renderDistance.farPlane(12));
    }

    private static PackConfig.ShadowSettings shadowSettings(Path shaders, String fragment) {
        return PackConfig.parse(List.of(new PackProgram("shadow", "#version 120\n" + fragment,
                shaders.resolve("shadow.fsh"), null, null)), shaders).shadowSettings();
    }
}
