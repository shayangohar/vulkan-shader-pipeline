package net.chimera.render.shader;

import net.chimera.shaderpack.PackBlendPlan;
import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.UniformRegistry;
import net.vulkanmod.vulkan.shader.PipelineState;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris's per-draw gtextureSize and blendFunc (CommonUniforms) and its selected block
 * (IrisExclusiveUniforms). No corpus pack reads them; they left UniformRegistry.IRIS_UNSERVED.
 */
final class IrisDrawUniformsTest {
    // Minecraft's translucent blend in VkBlendFactor values: SRC_ALPHA, 1 - SRC_ALPHA, ONE, 1 - SRC_ALPHA.
    private static final PipelineState.BlendInfo TRANSLUCENT = new PipelineState.BlendInfo(true, 6, 7, 1, 7, 0);

    @Test
    void aDrawReadsItsTextureSizeAndItsBlendAsGlFactors() {
        PackFrameState state = new PackFrameState();
        assertTrue(state.setDrawState(64, 32, null, TRANSLUCENT));
        assertFalse(state.setDrawState(64, 32, null, TRANSLUCENT), "an unchanged draw rewrites nothing");
        MappedBuffer buffer = new MappedBuffer(16);
        state.write(UniformRegistry.descriptor("gtextureSize"), "ivec2", buffer);
        assertEquals(64, buffer.getInt(0));
        assertEquals(32, buffer.getInt(4));
        state.write(UniformRegistry.descriptor("blendFunc"), "ivec4", buffer);
        assertEquals(0x0302, buffer.getInt(0));
        assertEquals(0x0303, buffer.getInt(4));
        assertEquals(1, buffer.getInt(8));
        assertEquals(0x0303, buffer.getInt(12));
    }

    /**
     * The program reports its directive in place of the host blend where Iris does
     * (PackPipelines.blendFuncOverride): an enabled one anywhere, an off one under Sodium only.
     */
    @Test
    void aReportedDirectiveReplacesTheHostBlend() {
        PackFrameState state = new PackFrameState();
        MappedBuffer buffer = new MappedBuffer(16);
        state.setDrawState(0, 0, new PackBlendPlan.Mode(true, 1, 1, 1, 0), TRANSLUCENT);
        state.write(UniformRegistry.descriptor("blendFunc"), "ivec4", buffer);
        assertEquals(1, buffer.getInt(0));
        assertEquals(0, buffer.getInt(12));
        state.setDrawState(0, 0, PackBlendPlan.Mode.OFF, TRANSLUCENT);
        state.write(UniformRegistry.descriptor("blendFunc"), "ivec4", buffer);
        assertEquals(0, buffer.getInt(0), "a Sodium program's off directive reads zero");
        state.setDrawState(0, 0, null, TRANSLUCENT);
        state.write(UniformRegistry.descriptor("blendFunc"), "ivec4", buffer);
        assertEquals(0x0302, buffer.getInt(0), "no reported directive reads the host blend");
        state.setDrawState(0, 0, null, new PipelineState.BlendInfo(false, 6, 7, 1, 7, 0));
        state.write(UniformRegistry.descriptor("blendFunc"), "ivec4", buffer);
        assertEquals(0, buffer.getInt(0), "blending off reads zero");
    }

    @Test
    void nothingSelectedReadsIrisDefaults() {
        PackFrameState state = new PackFrameState();
        MappedBuffer buffer = new MappedBuffer(12);
        state.write(UniformRegistry.descriptor("currentSelectedBlockId"), "int", buffer);
        assertEquals(0, buffer.getInt(0));
        state.write(UniformRegistry.descriptor("currentSelectedBlockPos"), "vec3", buffer);
        assertEquals(-256.0F, buffer.getFloat(0));
        assertEquals(-256.0F, buffer.getFloat(8));
    }

    /** A pass reads its directive's blend or zero, and cannot read the texture another pass left on unit 0. */
    @Test
    void aPassReadsItsBlendAsAConstantAndRefusesGtextureSize(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"),
                "blend.composite=SRC_ALPHA ONE_MINUS_SRC_ALPHA ONE ZERO\nblend.final=ONE ONE ONE ONE\n");
        String blendReader = """
                #version 120
                uniform sampler2D colortex0;
                uniform ivec4 blendFunc;
                varying vec2 uv;
                void main() { gl_FragData[0] = texture2D(colortex0, uv) * float(blendFunc.x); }
                """;
        Files.writeString(shaders.resolve("composite.fsh"), blendReader);
        Files.writeString(shaders.resolve("composite1.fsh"), blendReader);
        Files.writeString(shaders.resolve("final.fsh"), blendReader);
        Files.writeString(shaders.resolve("composite2.fsh"), blendReader
                .replace("ivec4 blendFunc", "ivec2 gtextureSize")
                .replace("blendFunc.x", "gtextureSize.x"));
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        assertTrue(analysis.plan().program("composite").convertedFragment().contains("ivec4(770, 771, 1, 0).x"),
                analysis.plan().program("composite").convertedFragment());
        assertTrue(analysis.plan().program("composite1").convertedFragment().contains("ivec4(0, 0, 0, 0).x"));
        assertTrue(analysis.plan().program("final").convertedFragment().contains("ivec4(0, 0, 0, 0).x"),
                "FinalPassRenderer applies no blend directive");
        PackProgramPlan sized = analysis.plan().program("composite2");
        assertTrue(!sized.executable() && sized.deviations().contains("UNIFORM_NAME_UNSUPPORTED:gtextureSize"),
                sized.deviations().toString());
    }
}
