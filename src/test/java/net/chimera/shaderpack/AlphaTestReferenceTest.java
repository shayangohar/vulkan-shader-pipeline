package net.chimera.shaderpack;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.chimera.render.TerrainLayers;
import net.chimera.render.shader.ChimeraSkyBridge;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris serves alphaTestRef as the program's current alpha-test reference
 * (IrisInternalUniforms, CapturedRenderingState.getCurrentAlphaTest), and its core transformers
 * rename the name to iris_currentAlphaTest. Glimmer, Escheridia, Ebin, Mellow and RenderPearl
 * read it; Chimera refused every program that did.
 */
final class AlphaTestReferenceTest {
    private static final String READER = """
            #version 120
            uniform sampler2D tex;
            uniform float alphaTestRef;
            varying vec2 uv;
            void main() {
                vec4 color = texture2D(tex, uv);
                if (color.a < alphaTestRef) discard;
                gl_FragData[0] = color;
            }
            """;

    /** The per-draw reference, a directive's fixed one, or 0 for a program Iris tests with ALWAYS. */
    @Test
    void alphaTestRefReadsTheProgramsReference() {
        String perDraw = PackAlphaTestPlan.forProgram("gbuffers_entities", settings(Map.of())).serveReference(READER);
        String reference = UniformRegistry.ENTITY_ALPHA_REFERENCE;
        assertTrue(!perDraw.contains("alphaTestRef") && perDraw.contains("uniform float " + reference + ";")
                && perDraw.contains("color.a < (" + reference + " < 0.0 ? 3.4028235e38 : " + reference + ")"),
                perDraw);
        String fixed = PackAlphaTestPlan.forProgram("gbuffers_entities",
                settings(Map.of("alphaTest.gbuffers_entities", "GREATER 0.5"))).serveReference(READER);
        assertTrue(fixed.contains("color.a < 0.5") && !fixed.contains("uniform float"), fixed);
        String off = PackAlphaTestPlan.forProgram("gbuffers_entities",
                settings(Map.of("alphaTest.gbuffers_entities", "off"))).serveReference(READER);
        assertTrue(off.contains("color.a < 0.0") && !off.contains("alphaTestRef"), off);
        String injected = PackAlphaTestPlan.forProgram("gbuffers_entities", settings(Map.of()))
                .injectDrawTest(perDraw);
        assertEquals(1, injected.split("uniform float " + reference + ";", -1).length - 1,
                "the injected test reuses the served declaration: " + injected);
    }

    /** Terrain draws and a shadow vertex read the reference; a post program still refuses it. */
    @Test
    void worldProgramsReadItAndPostProgramsRefuseIt(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_terrain.vsh"), """
                #version 120
                varying vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_terrain.fsh"), READER);
        Files.writeString(shaders.resolve("shadow.vsh"), """
                #version 120
                uniform float alphaTestRef;
                varying vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy + alphaTestRef; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("shadow.fsh"), READER);
        Files.writeString(shaders.resolve("composite.fsh"), READER.replace("varying vec2 uv;",
                "varying vec2 uv;\nuniform sampler2D colortex0;"));
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        for (String name : new String[] {"gbuffers_terrain", "shadow"}) {
            PackProgramPlan plan = analysis.plan().program(name);
            assertTrue(plan.executable() && !plan.convertedFragment().contains("alphaTestRef")
                    && !plan.convertedVertex().contains("alphaTestRef"), name + ": " + plan.deviations());
        }
        assertTrue(analysis.plan().program("gbuffers_terrain").convertedFragment()
                .contains(UniformRegistry.ENTITY_ALPHA_REFERENCE), "terrain reads the per-layer reference");
        assertTrue(analysis.plan().program("composite").deviations()
                .contains("UNIFORM_NAME_UNSUPPORTED:alphaTestRef"), "a post program keeps refusing it");
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), compiled.failures().toString());
    }

    /** Iris tests its terrain cutout pass at 0.1 and solid and translucent terrain not at all. */
    @Test
    void terrainLayersTakeIrisPassReferences() {
        assertEquals(0.1F, TerrainLayers.irisAlphaReference(TerrainRenderType.CUTOUT));
        assertEquals(0.1F, TerrainLayers.irisAlphaReference(TerrainRenderType.CUTOUT_MIPPED));
        assertEquals(0.0F, TerrainLayers.irisAlphaReference(TerrainRenderType.SOLID));
        assertEquals(0.0F, TerrainLayers.irisAlphaReference(TerrainRenderType.TRANSLUCENT));
        assertEquals(0.0F, TerrainLayers.irisAlphaReference(TerrainRenderType.TRIPWIRE));
    }

    /**
     * Iris tests gbuffers_skybasic's POSITION_COLOR draws (SKY_BASIC_COLOR, the sunrise fan) at
     * NON_ZERO_ALPHA and its POSITION draws (SKY_BASIC) not at all; other sky programs set their own.
     */
    @Test
    void skyDrawsTakeTheirKeysReference() {
        assertEquals(0.0001F, ChimeraSkyBridge.skyAlphaReference(true, DefaultVertexFormat.POSITION_COLOR));
        assertEquals(0.0F, ChimeraSkyBridge.skyAlphaReference(true, DefaultVertexFormat.POSITION));
        assertEquals(0.0F, ChimeraSkyBridge.skyAlphaReference(false, DefaultVertexFormat.POSITION_COLOR));
    }

    private static PackSettingsPlan settings(Map<String, String> properties) {
        return AlphaTestContractTest.settings(properties);
    }
}
