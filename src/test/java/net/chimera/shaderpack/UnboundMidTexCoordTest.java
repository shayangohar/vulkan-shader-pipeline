package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mc_midTexCoord on the host formats Iris does not widen. Iris binds it only in IrisVertexFormats
 * TERRAIN, ENTITY and GLYPH, so a particle, weather, line, glint, textured, lightning, leash, cloud,
 * text or boat-mask draw reads GL's unbound generic attribute, (0, 0, 0, 1). The measured case
 * (pegasus iris rain.rdc): Pegasus's weather rebuilds a sprite from it, and every rain vertex Iris
 * draws holds a mid-coordinate of (0, 0); Chimera served the vertex's own UV0 and lost the pack's look.
 */
final class UnboundMidTexCoordTest {
    @Test
    void particleAndWeatherVerticesReadTheUnboundDefault() {
        String vertex = """
                #version 120
                attribute vec4 mc_midTexCoord;
                varying vec2 quadCenter;
                varying vec2 texcoord;
                void main() {
                    texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).st;
                    quadCenter = (gl_TextureMatrix[0] * mc_midTexCoord).st;
                    gl_Position = ftransform();
                }
                """;
        String fragment = "#version 120\nvarying vec2 quadCenter;\nvarying vec2 texcoord;\n"
                + "void main() { gl_FragColor = vec4(quadCenter, texcoord); }\n";
        String converted = EntityVertexBridge.convertParticleVertexChecked(vertex, null, fragment, Map.of(),
                false, List.of()).source();
        assertTrue(converted.contains(GlslVertexPreambles.UNBOUND_MID_TEX_COORD), converted);
        assertTrue(converted.contains("vec4(MidTexCoord, 0.0, 1.0)"), "the vec4 form reads (0, 0, 0, 1)");
        assertFalse(converted.contains("#define MidTexCoord UV0"), "the mid-coordinate is not the vertex's own");
    }

    @Test
    void everyHostFormatIrisLeavesUnwidenedReadsIt() {
        List<String> unwidened = List.of(GlslVertexPreambles.PARTICLE_VERTEX_PREAMBLE,
                GlslVertexPreambles.LINE_VERTEX_PREAMBLE, GlslVertexPreambles.GLINT_VERTEX_PREAMBLE,
                GlslVertexPreambles.TEXTURED_VERTEX_PREAMBLE, GlslVertexPreambles.TEXTURED_COLOR_VERTEX_PREAMBLE,
                GlslVertexPreambles.LIGHTNING_VERTEX_PREAMBLE, GlslVertexPreambles.LEASH_VERTEX_PREAMBLE,
                GlslVertexPreambles.CLOUD_VERTEX_PREAMBLE, GlslVertexPreambles.TEXT_VERTEX_PREAMBLE,
                GlslVertexPreambles.WATER_MASK_VERTEX_PREAMBLE);
        for (String preamble : unwidened) {
            assertTrue(preamble.contains(GlslVertexPreambles.UNBOUND_MID_TEX_COORD), preamble);
        }
        assertTrue(GlslVertexPreambles.BEACON_VERTEX_PREAMBLE.contains("in vec2 MidTexCoord;"),
                "the beacon's block layout is widened to Iris's TERRAIN and keeps its own");
    }
}
