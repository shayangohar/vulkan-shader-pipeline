package net.chimera.shaderpack;

import net.chimera.render.shader.GlslCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RenderPearl 2.8's float16 path computes a NaN name-tag background; NVIDIA's GL driver then draws
 * the opaque glyphs over it, as their blend weighs the NaN by zero, while Vulkan keeps the NaN and
 * the whole tag went black. A geometry fragment's float outputs write 0 for NaN as main ends.
 */
final class NanOutputsTest {
    @Test
    void floatOutputsWriteZeroForNan() {
        String fragment = """
                #version 460
                #extension GL_AMD_gpu_shader_half_float : require
                layout(location = 0) out vec4 colortex1;
                layout(location = 1) out uvec4 colortex2;
                layout(location = 2) out f16vec3 colortex3;
                layout(location = 3) out float colortex4;
                layout(location = 4) out vec4 colortex5;
                layout(depth_unchanged) out float gl_FragDepth;
                layout(location = 0) in vec4 tint;
                void main() {
                    if (tint.a < 0.1) return;
                    colortex1 = tint / tint.a;
                    colortex2 = uvec4(1u);
                    colortex3 = f16vec3(tint.rgb);
                    colortex4 = 1.0 / tint.r;
                }
                """;
        String converted = FragmentConverter.withoutNanOutputs(fragment);
        assertTrue(converted.contains("vec4 chimeraNoNan(vec4 value) { return mix(value, vec4(0), isnan(value)); }")
                        && converted.contains("f16vec3 chimeraNoNan(f16vec3 value)")
                        && converted.contains("vec3 chimeraNoNan(vec3 value)")
                        && converted.contains("    colortex1 = chimeraNoNan(colortex1);\n")
                        && converted.contains("colortex3 = chimeraNoNan(colortex3);")
                        && converted.contains("colortex4 = chimeraNoNan(colortex4);")
                        && !converted.contains("chimeraNoNan(colortex2)"),
                converted);
        // RenderPearl redeclares gl_FragDepth depth_unchanged and never writes it; a write of its undefined
        // value broke every depth test. An output the program never writes stays unwritten too.
        assertTrue(!converted.contains("chimeraNoNan(gl_FragDepth)") && !converted.contains("chimeraNoNan(colortex5)"),
                converted);
        assertTrue(converted.indexOf("colortex4 = 1.0 / tint.r;") < converted.indexOf("colortex1 = chimeraNoNan")
                        && !converted.contains("chimeraAuthoredMain"),
                "the guard ends main's own body, where the alpha test follows it: " + converted);
        // RTX 5080: the half output compiles as vec3; AMD's GPU keeps it.
        for (String profile : List.of("nvidia", "amd")) {
            PackDevice before = PackDevice.current();
            PackDevice.set(PackDevice.offline(profile));
            try {
                MemoryUtil.memFree(GlslCompiler.compile("nan.fsh", converted, Shaderc.shaderc_glsl_fragment_shader,
                        options -> {}));
            } finally {
                PackDevice.set(before);
            }
        }
    }

    /** Post passes write what they compute: only geometry programs take the epilogue. */
    @Test
    void geometryProgramsTakeItAndPostPassesDoNot(@TempDir Path root) throws Exception {
        Path shaders = Files.createDirectory(root.resolve("shaders"));
        Files.writeString(shaders.resolve("gbuffers_textured.vsh"), """
                #version 120
                varying vec4 color;
                void main() { color = gl_Color; gl_Position = ftransform(); }
                """);
        Files.writeString(shaders.resolve("gbuffers_textured.fsh"), """
                #version 120
                varying vec4 color;
                void main() { gl_FragData[0] = color; }
                """);
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
        var analysis = PackProbe.analyze(root);
        assertTrue(analysis.plan().program("gbuffers_textured").convertedFragment().contains("isnan("),
                analysis.plan().program("gbuffers_textured").convertedFragment());
        assertTrue(!analysis.plan().program("composite").convertedFragment().contains("isnan("),
                analysis.plan().program("composite").convertedFragment());
        var compiled = PackCompileTest.run(root, analysis);
        assertTrue(compiled.failures().isEmpty(), "compilation: " + compiled.failures());
    }
}
