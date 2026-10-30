package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A shadowcomp fragment pass plans as a fullscreen program over the shadowcolor targets, as Iris's
 * ShadowCompositeRenderer runs it. Kappa's shadowcomp turns the shadow pass's albedo into the light
 * transmittance its lighting decodes; without it Kappa lit the End and water with the raw albedo.
 */
final class ShadowcompProgramTest {
    @Test
    void aShadowcompPassPlansOverTheShadowcolorTargets(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        writeShadowcomp(shaders);
        writeComposite(shaders);
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackProgramPlan shadowcomp = analysis.plan().program("shadowcomp");

        assertTrue(shadowcomp.executable(), "shadowcomp did not plan: " + shadowcomp.deviations());
        assertNotNull(shadowcomp.convertedFragment(), "shadowcomp has no converted fragment");
        assertTrue(shadowcomp.deviations().stream().noneMatch(d -> d.startsWith("PASS_PROGRAM_UNSUPPORTED")),
                shadowcomp.deviations().toString());
        // No directive: ShadowCompositeRenderer draws {0, 1}; the source writes only location 0.
        assertEquals(List.of(0, 1), shadowcomp.targetPlan().targetSlots());
        assertEquals(List.of(0), shadowcomp.targetPlan().outputLocations());
        assertEquals(analysis.config().shadowSettings().colorAttachmentFormats(),
                shadowcomp.targetPlan().outputFormats(), "shadowcomp must draw in the shadowcolor formats");

        PackTargetGraphPlan graph = PackTargetGraphPlan.build(
                analysis.plan().programs(), analysis.config(), 16, 16, 8, 16384);
        assertNull(graph.step("shadowcomp"), "shadowcomp must stay out of the colortex graph");
        assertNotNull(graph.step("composite"), "the colortex graph lost its composite");
        assertSame(IrisSamplerOrder.SHADOWCOMP, IrisSamplerOrder.of("shadowcomp1", false),
                "a shadowcomp program registers its samplers as ShadowCompositeRenderer.createProgram does");
    }

    @Test
    void anAuthoredDirectiveKeepsItsShadowcolorTargets() {
        PostTargetPlan plan = PostTargetPlan.parseShadowcomp("shadowcomp", """
                /* RENDERTARGETS: 1 */
                layout(location = 0) out vec4 color;
                void main() { color = vec4(1.0); }
                """, null).plan();
        assertEquals(List.of(1), plan.targetSlots());
    }

    private static void writeShadowcomp(Path shaders) throws Exception {
        Files.writeString(shaders.resolve("shadowcomp.vsh"), """
                #version 430 compatibility
                out vec2 uv;
                void main() {
                    gl_Position = vec4(gl_Vertex.xy * 2.0 - 1.0, 0.0, 1.0);
                    uv = gl_MultiTexCoord0.xy;
                }
                """);
        Files.writeString(shaders.resolve("shadowcomp.fsh"), """
                #version 430 compatibility
                layout(location = 0) out vec4 data0;
                uniform sampler2D shadowtex0;
                uniform sampler2D shadowtex1;
                uniform sampler2D shadowcolor0;
                in vec2 uv;
                void main() {
                    vec4 albedo = texture(shadowcolor0, uv);
                    bool translucent = texture(shadowtex0, uv).x < texture(shadowtex1, uv).x;
                    data0 = translucent ? albedo * vec4(vec3(0.25), 1.0) : vec4(vec3(0.25), 0.0);
                }
                """);
    }

    private static void writeComposite(Path shaders) throws Exception {
        Files.writeString(shaders.resolve("composite.vsh"), """
                #version 120
                varying vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                uniform sampler2D colortex0;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(colortex0, uv); }
                """);
    }
}
