package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Alpha Piscium's "Assume NVIDIA GPU" enables GL_NV_gpu_shader5 for half floats and 16-bit
 * integers; shaderc refused it ("syntax error, unexpected IDENTIFIER" at float16_t), and the whole
 * screen went black.
 */
final class GlslVendorExtensionsTest {
    private static final GlslVendorExtensions.SizedTypes ALL = new GlslVendorExtensions.SizedTypes(true, true, true);
    private static final String COMPUTE = """
            #version 450
            #extension GL_NV_gpu_shader5 : enable
            layout(local_size_x = 1) in;
            layout(std430, binding = 0) buffer Data { vec4 value; };
            void main() {
                f16vec4 half4 = f16vec4(value);
                float16_t edgeWeight = half4.x * float16_t(2.0);
                u16vec2 count = u16vec2(1, 2);
                int64_t wide = int64_t(count.y);
                uint packed = packFloat2x16(half4.xy);
                float weight = float(smoothstep(0.0, 1.0, edgeWeight)) + float(clamp(edgeWeight, 0.0, 1.0));
                value = vec4(half4) + weight + float(count.y) + float(wide) + float(packed);
            }
            """;

    /**
     * The NVIDIA extension compiles as the Vulkan extensions for its types, and a built-in call that
     * mixes float16_t with float literals resolves as NVIDIA's compiler resolves it.
     */
    @Test
    void theNvidiaExtensionCompilesAsItsVulkanTypes() {
        String translated = GlslVendorExtensions.forVulkan(COMPUTE, ALL);
        assertTrue(translated.contains("#extension GL_AMD_gpu_shader_half_float : enable\n"
                + "#extension GL_AMD_gpu_shader_int16 : enable\n"
                + "#extension GL_ARB_gpu_shader_int64 : enable\n"), translated);
        assertEquals("", compile(translated, Shaderc.shaderc_glsl_compute_shader));
    }

    /**
     * A directive with a trailing comment stayed as written: GL_NV_gpu_shader5 unknown to shaderc,
     * and GL_AMD_shader_trinary_minmax required of a device without it. Both are rewritten, the
     * comment kept on the first line written in the directive's place.
     */
    @Test
    void aDirectiveWithATrailingCommentIsRewritten() {
        String nvidia = GlslVendorExtensions.forVulkan(
                COMPUTE.replace(": enable\n", ": enable // NVIDIA path\n"), ALL);
        assertTrue(nvidia.contains("#extension GL_AMD_gpu_shader_half_float : enable // NVIDIA path\n"
                + "#extension GL_AMD_gpu_shader_int16 : enable\n") && !nvidia.contains("GL_NV_gpu_shader5"), nvidia);
        assertEquals("", compile(nvidia, Shaderc.shaderc_glsl_compute_shader));
        String amd = GlslVendorExtensions.forVulkan("""
                #version 450
                #extension GL_AMD_shader_trinary_minmax : require // AMD path
                layout(local_size_x = 1) in;
                layout(std430, binding = 0) buffer Data { float value; };
                void main() { value = max3(value, 1.0, 2.0); }
                """, GlslVendorExtensions.SizedTypes.NONE, false);
        assertTrue(!amd.contains("#extension GL_AMD_shader_trinary_minmax")
                && amd.contains("#define min3(a, b, c) min(a, min(b, c)) // AMD path\n"), amd);
        assertEquals("", compile(amd, Shaderc.shaderc_glsl_compute_shader));
    }

    /** A converted stage keeps the pack's directive below Chimera's declarations; it still compiles. */
    @Test
    void aDirectiveAfterDeclarationsCompiles() {
        String fragment = """
                #version 460
                layout(location = 0) out vec4 color;
                #extension GL_NV_gpu_shader5 : enable
                void main() { f16vec4 half4 = f16vec4(0.5); color = vec4(max(half4, f16vec4(0.25))); }
                """;
        assertEquals("", compile(GlslVendorExtensions.forVulkan(fragment, ALL), Shaderc.shaderc_glsl_fragment_shader));
    }

    /** Without every type the extension provides, the directive stays, as on a GL driver without it. */
    @Test
    void aDeviceWithoutTheTypesKeepsTheDirective() {
        GlslVendorExtensions.SizedTypes halfOnly = new GlslVendorExtensions.SizedTypes(true, false, false);
        assertEquals(COMPUTE, GlslVendorExtensions.forVulkan(COMPUTE, halfOnly));
        assertEquals(COMPUTE, GlslVendorExtensions.forVulkan(COMPUTE, GlslVendorExtensions.SizedTypes.NONE));
    }

    /**
     * RenderPearl's NVIDIA path writes {@code out f16vec4 colortex1}, which a Vulkan device without
     * storageInputOutput16, like the RTX 5080, cannot take. Stage inputs and outputs widen to their
     * 32-bit types; parameters, locals and uniforms keep theirs.
     */
    @Test
    void sixteenBitStageInterfacesWidenWithoutStorageInputOutput16() {
        String fragment = """
                #version 460
                #extension GL_AMD_gpu_shader_half_float : require
                #extension GL_AMD_gpu_shader_int16 : require
                layout(location = 0) out f16vec4 colortex1;
                layout(location = 1) flat in u16vec2 packedLight;
                in Data { layout(location = 2) f16vec3 normal; } v;
                layout(std140, binding = 0) uniform Exposure { vec4 scale; };
                f16vec4 shade(in f16vec3 color, out float16_t weight) {
                    weight = float16_t(0.5);
                    return f16vec4(color * v.normal, weight);
                }
                void main() {
                    float16_t weight;
                    f16vec4 color = shade(f16vec3(scale.rgb) + float16_t(packedLight.x), weight);
                    colortex1 = color * weight;
                }
                """;
        String widened = GlslVendorExtensions.forVulkan(fragment, PackDevice.offline("nvidia"));
        assertTrue(widened.contains("layout(location = 0) out vec4 colortex1;")
                        && widened.contains("layout(location = 1) flat in uvec2 packedLight;")
                        && widened.contains("in Data { layout(location = 2) vec3 normal; } v;")
                        && widened.contains("f16vec4 shade(in f16vec3 color, out float16_t weight)")
                        && widened.contains("float16_t weight;"),
                widened);
        assertEquals("", compile(widened, Shaderc.shaderc_glsl_fragment_shader));
        assertEquals(fragment, GlslVendorExtensions.forVulkan(fragment, PackDevice.offline("amd")),
                "AMD's GPU takes 16-bit stage interfaces");
    }

    /**
     * Chimera declares RenderPearl's outputs ahead of its source, where AMD's path makes them f16vec3
     * before its half-float directive: "syntax error, unexpected IDENTIFIER". The directives move up
     * to follow the version line; one in a comment stays, and a source already in order is unchanged.
     */
    @Test
    void extensionsMoveAheadOfTheDeclarationsBeforeThem() {
        String fragment = """
                #version 460
                layout(location = 0) out f16vec3 chimeraFragColor0;
                /* #extension GL_NV_gpu_shader5 : require */
                // #extension GL_ARB_gpu_shader_int64 : require
                	#extension GL_AMD_gpu_shader_half_float : require
                void main() { chimeraFragColor0 = f16vec3(1.0); }
                """;
        String hoisted = GlslVendorExtensions.hoistExtensions(fragment);
        assertEquals("""
                #version 460
                	#extension GL_AMD_gpu_shader_half_float : require
                layout(location = 0) out f16vec3 chimeraFragColor0;
                /* #extension GL_NV_gpu_shader5 : require */
                // #extension GL_ARB_gpu_shader_int64 : require
                void main() { chimeraFragColor0 = f16vec3(1.0); }
                """, hoisted);
        assertEquals("", compile(GlslVendorExtensions.forVulkan(fragment, PackDevice.offline("amd")),
                Shaderc.shaderc_glsl_fragment_shader));
        assertEquals(hoisted, GlslVendorExtensions.hoistExtensions(hoisted), "a source in order stays as it is");
    }

    /** shaderc's report, or empty when the source compiles for Vulkan 1.2. */
    private static String compile(String source, int kind) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(options,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
        long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, "vendor.glsl", "main", options);
        try {
            if (result == 0) return "no shader result";
            return Shaderc.shaderc_result_get_compilation_status(result) == Shaderc.shaderc_compilation_status_success
                    ? "" : Shaderc.shaderc_result_get_error_message(result);
        } finally {
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }
}
