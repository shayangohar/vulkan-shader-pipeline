package net.chimera.render.shader;

import net.chimera.shaderpack.PackProbe;
import net.chimera.shaderpack.PackProgramPlan;
import net.chimera.shaderpack.UniformRegistry;
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
 * Iris's fog uniforms (FogUniforms, and IrisInternalUniforms' for gl_Fog) and cloudTime
 * (IrisExclusiveUniforms). RedHat's world programs read fogMode and Shrimple's read fogShape and
 * cloudTime; Chimera refused all three.
 */
final class IrisFogUniformsTest {
    /** Before any fog setup Iris's captured density is 0 (CapturedRenderingState): GL_EXP2, density 0. */
    @Test
    void aWorldProgramReadsTheCapturedFog() {
        PackFrameState state = new PackFrameState();
        MappedBuffer buffer = new MappedBuffer(4);
        state.write(UniformRegistry.descriptor("fogMode"), "int", buffer);
        assertEquals(0x0801, buffer.getInt(0), "GL_EXP2");
        state.write(UniformRegistry.descriptor("fogDensity"), "float", buffer);
        assertEquals(0.0F, buffer.getFloat(0));
        state.write(UniformRegistry.descriptor("fogShape"), "int", buffer);
        assertEquals(0, buffer.getInt(0), "spherical before any capture");
        state.write(UniformRegistry.descriptor("cloudTime"), "float", buffer);
        assertEquals(0.0F, buffer.getFloat(0), "no ticks before a level renders");
    }

    /** Each fog setup serves its range, as Miniature's Nether reads start 12.8 and end 96 under Iris. */
    @Test
    void theFogRangeFollowsEachSetup() {
        PackFrameState state = new PackFrameState();
        MappedBuffer buffer = new MappedBuffer(4);
        assertTrue(state.setFogRange(12.8F, 96.0F));
        assertFalse(state.setFogRange(12.8F, 96.0F), "an unchanged range rewrites nothing");
        state.write(UniformRegistry.descriptor("fogStart"), "float", buffer);
        assertEquals(12.8F, buffer.getFloat(0));
        state.write(UniformRegistry.descriptor("fogEnd"), "float", buffer);
        assertEquals(96.0F, buffer.getFloat(0));
    }

    /**
     * gl_Fog's range is PER_FRAME in Iris: each program reads it at its first draw in a frame. The
     * horizon draws gbuffers_skybasic first, under the start setupNoFog left, and the sky disc it
     * draws after setupFog(FOG_SKY) keeps that range; a program first drawn after the setup reads
     * the sky fog, and a compute, run once a frame, reads the current range.
     */
    @Test
    void eachProgramLatchesItsFirstFog() {
        PackFrameState state = new PackFrameState();
        Object skyBasic = new Object();
        Object skyTextured = new Object();
        state.beginFogFrame();
        state.setFogRange(Float.MAX_VALUE, 192.0F);
        assertTrue(state.latchProgramFog(skyBasic));
        state.setFogRange(0.0F, 192.0F);
        assertTrue(state.latchProgramFog(skyTextured));
        assertEquals(0.0F, read(state, "iris_FogStart"));
        assertTrue(state.latchProgramFog(skyBasic));
        assertEquals(Float.MAX_VALUE, read(state, "iris_FogStart"), "the sky disc keeps the horizon's range");
        assertEquals(192.0F, read(state, "iris_FogEnd"));
        assertFalse(state.latchProgramFog(skyBasic), "an unchanged latch rewrites nothing");
        state.beginFogFrame();
        state.latchProgramFog(skyBasic);
        assertEquals(0.0F, read(state, "iris_FogStart"), "the next frame latches afresh");
        state.setFogRange(12.8F, 96.0F);
        state.latchProgramFog(null);
        assertEquals(12.8F, read(state, "iris_FogStart"), "a compute reads the current range");
    }

    /**
     * The latches are the programs', which Iris rebuilds with a pack: a program drawn again after the
     * session ends latches afresh within the frame. They were static, so they outlived the session,
     * and one test's latches reached the next.
     */
    @Test
    void aNewSessionLatchesAfresh() {
        PackFrameState state = new PackFrameState();
        Object program = new Object();
        state.beginFogFrame();
        state.setFogRange(Float.MAX_VALUE, 192.0F);
        state.latchProgramFog(program);
        state.resetSession();
        state.setFogRange(0.0F, 192.0F);
        state.latchProgramFog(program);
        assertEquals(0.0F, read(state, "iris_FogStart"), "the old session's latch is gone");
    }

    private static float read(PackFrameState state, String name) {
        MappedBuffer buffer = new MappedBuffer(4);
        state.write(UniformRegistry.descriptor(name), "float", buffer);
        return buffer.getFloat(0);
    }

    /** The frame's fog colour, latched once setupColor has set it (Iris's per-frame fogColor). */
    @Test
    void theFogColourIsTheFramesOwn() {
        PackFrameState state = new PackFrameState();
        MappedBuffer buffer = new MappedBuffer(16);
        float[] water = {0.0686F, 0.0686F, 0.6998F, 1.0F};
        assertTrue(state.setFogColor(water));
        assertFalse(state.setFogColor(water.clone()), "an unchanged colour rewrites nothing");
        state.write(UniformRegistry.descriptor("fogColor"), "vec3", buffer);
        assertEquals(0.6998F, buffer.getFloat(8));
        state.write(UniformRegistry.descriptor("iris_FogColor"), "vec4", buffer);
        assertEquals(1.0F, buffer.getFloat(12), "gl_Fog.color is the fog RGBA");
    }

    /** A world program plans the live values; a post program reads FogMode.OFF's constants. */
    @Test
    void aPostProgramReadsFogModeOff(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        String reader = """
                #version 120
                uniform int fogMode;
                uniform int fogShape;
                uniform float fogDensity;
                uniform float cloudTime;
                varying vec2 uv;
                void main() { gl_FragData[0] = vec4(float(fogMode + fogShape), fogDensity, cloudTime, 1.0); }
                """;
        Files.writeString(shaders.resolve("gbuffers_basic.vsh"), """
                #version 120
                varying vec2 uv;
                void main() { uv = gl_MultiTexCoord0.xy; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_basic.fsh"), reader);
        Files.writeString(shaders.resolve("composite.fsh"), reader.replace("varying vec2 uv;", "varying vec2 uv;\n"
                + "uniform sampler2D colortex0;"));
        PackProbe.Analysis analysis = PackProbe.analyze(root);
        PackProgramPlan world = analysis.plan().program("gbuffers_basic");
        assertTrue(world.executable() && world.deviations().stream().noneMatch(d -> d.contains("UNSUPPORTED"))
                && world.convertedFragment().contains("fogMode"), world.deviations().toString());
        PackProgramPlan post = analysis.plan().program("composite");
        assertTrue(post.executable() && post.convertedFragment().contains("float(0 + -1)")
                && !post.convertedFragment().contains("fogMode"), post.convertedFragment());
    }
}
