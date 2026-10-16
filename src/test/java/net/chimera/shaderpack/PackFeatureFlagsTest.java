package net.chimera.shaderpack;

import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Iris's feature flags over what Chimera provides: defined where Iris defines them, refused where Iris refuses. */
final class PackFeatureFlagsTest {
    @Test
    void shadersPropertiesSeesTheFeaturesChimeraProvides(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("shaders.properties"), """
                iris.features.optional = BLOCK_EMISSION_ATTRIBUTE FADE_VARIABLE
                #if defined IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE
                size.buffer.colortex1 = 0.5 0.5
                #endif
                #ifdef IRIS_FEATURE_FADE_VARIABLE
                program.composite.enabled = false
                #endif
                """);
        PackSettingsPlan settings = PackSettingsPlan.parse(List.of(), dir, Map.of(),
                PackOptionEnvironment.defaults(dir));
        assertEquals("0.5 0.5", settings.propertyValues().get("size.buffer.colortex1"),
                "Complementary sizes its reflection buffers this way under Iris");
        assertTrue(settings.enabled("composite"), "a feature Chimera lacks reads as absent");
        assertEquals("1", settings.preprocessorDefines().get("IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE"));
        assertFalse(settings.preprocessorDefines().containsKey("IRIS_FEATURE_FADE_VARIABLE"),
                "mc_chunkFade does not exist, so packs must not take its path");
    }

    @Test
    @Tag("packs")
    void complementarySizesItsReflectionBuffersAsUnderIris() throws IOException {
        try (PackSource.LoadResult loaded = PackSource.loadResult(TestPacks.complementary())) {
            PackConfig.PackConfigData config = PackProbe.analyze(TestPacks.complementary(), loaded).config();
            for (int target : List.of(1, 7)) {
                assertEquals("0.5 0.5", config.targetSettings().get(target).sizeExpression(),
                        "colortex" + target + " follows REFLECTION_RES behind IRIS_FEATURE_BLOCK_EMISSION_ATTRIBUTE");
            }
        }
    }

    @Test
    void requiredFeaturesChimeraCannotProvideRefuseThePack() {
        assertEquals(Set.of("TESSELATION_SHADERS", "SEPARATE_HARDWARE_SAMPLERS", "A,B"),
                PackFeatureFlags.unprovided(List.of("TESSELATION_SHADERS", "SEPARATE_HARDWARE_SAMPLERS", "A,B",
                        "ssbo", "COMPUTE_SHADERS", "CUSTOM_IMAGES")),
                "unknown names refuse too; custom images load and fall back where they are missing");
        assertEquals("Tessellation shaders", PackFeatureFlags.readableName("TESSELATION_SHADERS"));
    }

    @Test
    void optionalFlagsAreDefinedAsThePackSpellsThem() {
        assertEquals(Map.of("IRIS_FEATURE_ssbo", "1", "IRIS_FEATURE_ENTITY_TRANSLUCENT", "1"),
                PackFeatureFlags.sourceDefines(List.of("ssbo", "ENTITY_TRANSLUCENT", "CUSTOM_IMAGES",
                        "HIGHER_SHADOWCOLOR")),
                "custom images stay with their own negotiation");
    }
}
