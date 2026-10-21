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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Iris's fog uniforms (FogUniforms) and cloudTime (IrisExclusiveUniforms). RedHat's world
 * programs read fogMode and Shrimple's read fogShape and cloudTime; Chimera refused all three.
 */
final class IrisFogUniformsTest {
    /** Out of water the captured density is -1: fogMode is GL_LINEAR and fogDensity 0. */
    @Test
    void aWorldProgramReadsTheCapturedFog() {
        PackFrameState state = new PackFrameState();
        MappedBuffer buffer = new MappedBuffer(4);
        state.write(UniformRegistry.descriptor("fogMode"), "int", buffer);
        assertEquals(0x2601, buffer.getInt(0), "GL_LINEAR");
        state.write(UniformRegistry.descriptor("fogDensity"), "float", buffer);
        assertEquals(0.0F, buffer.getFloat(0), "a negative density reads 0");
        state.write(UniformRegistry.descriptor("fogShape"), "int", buffer);
        assertEquals(0, buffer.getInt(0), "spherical before any capture");
        state.write(UniformRegistry.descriptor("cloudTime"), "float", buffer);
        assertEquals(0.0F, buffer.getFloat(0), "no ticks before a level renders");
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
