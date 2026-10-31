package net.chimera.shaderpack;

import net.chimera.shaderpack.options.PendingOptions;
import net.chimera.testing.TestPacks;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the pack's shadow pass runs and replaces vanilla entity shadows: Iris's ShadowRenderer
 * rule (shadow.enabled, or a shadow target read outside the shadow pass, gbuffers included), plus
 * a shadow pass that writes pack images, which Iris skips.
 */
final class ShadowPassUseTest {
    private static final String SHADOW_VSH = "#version 120\nvoid main() { gl_Position = ftransform(); }\n";
    private static final String SHADOW_FSH = "#version 120\nvoid main() { gl_FragData[0] = vec4(1.0); }\n";
    private static final String TERRAIN_VSH = """
            #version 120
            varying vec2 uv;
            void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
            """;
    private static final String TERRAIN_FSH = """
            #version 120
            uniform sampler2D tex;
            varying vec2 uv;
            void main() { gl_FragData[0] = texture2D(tex, uv); }
            """;
    private static final String TERRAIN_SHADOWED_FSH = """
            #version 120
            uniform sampler2D tex;
            uniform sampler2D shadowtex0;
            varying vec2 uv;
            void main() { gl_FragData[0] = texture2D(tex, uv) * texture2D(shadowtex0, uv).r; }
            """;

    /** A gbuffers program alone reading shadowtex0 builds Iris's ShadowRenderer: the ShaderMap creates the targets. */
    @Test
    void aGbuffersReaderRunsThePass(@TempDir Path root) throws Exception {
        ShadowPassUse use = analyze(root, "", TERRAIN_SHADOWED_FSH);
        assertTrue(use.mapRead() && use.runs() && use.replacesEntityShadows(), use.toString());
    }

    /** Mellow at its defaults: a shadow program nothing reads, which Iris never draws. */
    @Test
    void anUnreadShadowPassDoesNotRun(@TempDir Path root) throws Exception {
        ShadowPassUse use = analyze(root, "", TERRAIN_FSH);
        assertFalse(use.runs() || use.replacesEntityShadows(), use.toString());
    }

    /** A disabled program reads nothing: Iris removes it from the pipeline. */
    @Test
    void aDisabledReaderReadsNothing(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.vsh"), TERRAIN_VSH);
        Files.writeString(shaders.resolve("composite.fsh"), TERRAIN_SHADOWED_FSH);
        assertTrue(analyze(root, "", TERRAIN_FSH).runs(), "an enabled composite reader runs the pass");
        assertFalse(analyze(root, "program.composite.enabled=false\n", TERRAIN_FSH).runs());
    }

    /**
     * A shadow program that only stores into a pack image (light voxels) still runs, as the pack
     * means it to, though Iris skips it. Nothing samples a shadow map, so vanilla entity shadows stay.
     */
    @Test
    void aShadowPassThatWritesAPackImageRuns(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shadow.vsh"), """
                #version 430 compatibility
                layout(rgba8) uniform image3D voxels;
                void main() { imageStore(voxels, ivec3(0), vec4(1.0)); gl_Position = ftransform(); }
                """);
        ShadowPassUse use = analyze(root,
                "image.voxels = voxelsSampler RGBA RGBA8 UNSIGNED_BYTE false false 16 16 16\n", TERRAIN_FSH);
        assertTrue(use.sideEffect() && use.runs() && !use.replacesEntityShadows(), use.toString());
    }

    /** shadow.enabled as Iris reads it: false stops even a read pass, true forces an unread one. */
    @Test
    void shadowEnabledDecidesFirst(@TempDir Path root) throws Exception {
        ShadowPassUse off = analyze(root.resolve("off"), "shadow.enabled=false\n", TERRAIN_SHADOWED_FSH);
        assertEquals(ShadowPassUse.Enabled.FALSE, off.enabled());
        assertFalse(off.runs() || off.replacesEntityShadows(), off.toString());
        ShadowPassUse on = analyze(root.resolve("on"), "shadow.enabled = true\n", TERRAIN_FSH);
        assertTrue(on.runs() && on.replacesEntityShadows(), on.toString());
        ShadowPassUse malformed = analyze(root.resolve("bad"), "shadow.enabled=yes\n", TERRAIN_FSH);
        assertEquals(ShadowPassUse.Enabled.UNSET, malformed.enabled());
        assertTrue(malformed.deviations().contains("SHADOW_DIRECTIVE_MALFORMED:shadow.enabled"),
                malformed.deviations().toString());
    }

    /**
     * Mellow draws its shadow pass only for DYNAMIC_SHADOWS or COLORED_LIGHTS. For the voxels alone it
     * reads shadowtex0 in deferred1.vsh behind {@code gl_VertexID == -1e6} ("Guarantee the shadowmap
     * runs for colored lights to work"), so Iris builds its ShadowRenderer and drops vanilla entity
     * shadows there too.
     */
    @Test
    @Tag("packs")
    void mellowRunsItsShadowPassOnlyWhenAnOptionUsesIt(@TempDir Path root) throws Exception {
        Path source = TestPacks.extra().stream()
                .filter(path -> path.getFileName().toString().startsWith("Mellow")).findFirst().orElse(null);
        Assumptions.assumeTrue(source != null, "Mellow is not among chimera.pack.extra");
        Path pack = Files.copy(source, root.resolve(source.getFileName()));
        ShadowPassUse defaults = analyzeWith(pack, Map.of());
        assertFalse(defaults.runs() || defaults.replacesEntityShadows(), defaults.toString());
        ShadowPassUse shadows = analyzeWith(pack, Map.of("DYNAMIC_SHADOWS", "true"));
        assertTrue(shadows.runs() && shadows.replacesEntityShadows(), shadows.toString());
        ShadowPassUse lights = analyzeWith(pack, Map.of("COLORED_LIGHTS", "true"));
        assertTrue(lights.readers().contains("deferred1:shadowtex0") && lights.sideEffect() && lights.runs()
                && lights.replacesEntityShadows(), lights.toString());
    }

    /** A pack with shadow.vsh/fsh, gbuffers_terrain with {@code terrain} as its fragment, and these properties. */
    private static ShadowPassUse analyze(Path root, String properties, String terrain) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Map<String, String> files = new LinkedHashMap<>();
        files.put("shaders.properties", properties);
        files.put("gbuffers_terrain.vsh", TERRAIN_VSH);
        files.put("gbuffers_terrain.fsh", terrain);
        files.putIfAbsent("shadow.fsh", SHADOW_FSH);
        if (!Files.exists(shaders.resolve("shadow.vsh"))) files.put("shadow.vsh", SHADOW_VSH);
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(shaders.resolve(file.getKey()), file.getValue());
        }
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        return ShadowPassUse.of(analysis.plan(), analysis.passComputes());
    }

    private static ShadowPassUse analyzeWith(Path pack, Map<String, String> values) throws Exception {
        try (PackSource.LoadResult loaded = PackSource.loadResult(pack, "minecraft:overworld",
                new PendingOptions.Drained(values, false))) {
            PackProbe.Analysis analysis = PackProbe.analyze(pack, loaded);
            return ShadowPassUse.of(analysis.plan(), analysis.passComputes());
        }
    }
}
