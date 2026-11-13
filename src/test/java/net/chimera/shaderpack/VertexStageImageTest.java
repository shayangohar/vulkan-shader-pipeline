package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Images in an entity-family vertex stage. Iris binds images in every stage: Pegasus 0.8.1 with
 * PARTICLES_LPV=1 stores particle light into cimage3_colored_light from gbuffers_particles' vertex.
 * The bridge keeps the declaration of an image the plan binds; the program's image binding
 * (PackDescriptorContracts.bindStorageImages) then gives it its descriptor.
 */
final class VertexStageImageTest {
    private static final String VERTEX = """
            #version 430 compatibility
            layout (rgba8) uniform image3D cimage3_colored_light;
            varying vec2 texcoord;
            void main() {
                texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).st;
                imageStore(cimage3_colored_light, ivec3(gl_Vertex.xyz), vec4(1.0));
                gl_Position = ftransform();
            }
            """;
    private static final String FRAGMENT = "#version 430 compatibility\nvarying vec2 texcoord;\n"
            + "void main() { gl_FragColor = vec4(texcoord, 0.0, 1.0); }\n";

    @Test
    void aParticleVertexStoresToAnImageThePlanBinds() {
        String converted = convert(Set.of("cimage3_colored_light")).source();
        assertTrue(PackPipelines.STORAGE_IMAGE_DECLARATION.matcher(converted).find(), converted);
        assertTrue(converted.contains("imageStore(cimage3_colored_light"), converted);
    }

    @Test
    void anImageThePlanDoesNotBindStillRefusesTheStage() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> convert(Set.of()));
        assertEquals("unsupported entity vertex sampler: cimage3_colored_light", refused.getMessage());
    }

    private static LegacyGlslConverter.TerrainVertexConversion convert(Set<String> images) {
        UniformRegistry.ProgramInterface vertex = InterfacePlanner.plan(VERTEX, UniformRegistry.Stage.PARTICLE);
        return EntityVertexBridge.convertParticleVertexChecked(VERTEX, null, FRAGMENT, Map.of(), false, List.of(),
                new EntityVertexBridge.VertexSamplerPlan(vertex, new int[0], images));
    }
}
