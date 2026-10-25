package net.chimera.shaderpack;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GL_NV_gpu_shader5, the extension NVIDIA's GL drivers offer for explicitly sized arithmetic types,
 * as Vulkan GLSL provides them. Alpha Piscium's "Assume NVIDIA GPU" enables it for float16_t and
 * f16vecN, and Iris defines MC_GL_NV_gpu_shader5 on such a driver; shaderc does not know it, and
 * failed every stage at its first float16_t. The directive becomes the extensions below when the
 * device enables their types; otherwise it stays, and the stage fails to compile as on a GL driver
 * without it.
 */
public final class GlslVendorExtensions {
    private GlslVendorExtensions() {}

    /** The sized arithmetic types the device enables: shaderFloat16, shaderInt16, shaderInt64. */
    public record SizedTypes(boolean float16, boolean int16, boolean int64) {
        public static final SizedTypes NONE = new SizedTypes(false, false, false);

        boolean all() {
            return float16 && int16 && int64;
        }
    }

    private static final String NVIDIA = "GL_NV_gpu_shader5";
    /**
     * Its half-float, 16-bit and 64-bit types in Vulkan GLSL; for Vulkan 1.2 each emits only its
     * capability (Float16, Int16, Int64), and no vendor SPIR-V extension. These keep glslang's GLSL
     * 4.00 overload rules, which resolve a built-in call that mixes float16_t with float literals
     * (Alpha Piscium's smoothstep(0.0, 1.0, edgeWeight)) as NVIDIA's compiler does. Any
     * GL_EXT_shader_explicit_arithmetic_types extension turns on glslang's explicit-types rules,
     * which call that call ambiguous, so the 8-bit types, which only those extensions provide, stay
     * out: a stage that uses them fails as before.
     */
    private static final List<String> VULKAN = List.of("GL_AMD_gpu_shader_half_float", "GL_AMD_gpu_shader_int16",
            "GL_ARB_gpu_shader_int64");
    /** An {@code #extension name : behaviour} line: the text before the name, the name, the rest. */
    private static final Pattern EXTENSION = Pattern.compile(
            "(?m)^([ \\t]*#[ \\t]*extension[ \\t]+)(\\w+)([ \\t]*:[ \\t]*\\w+[ \\t]*)$");

    /** {@code source} with GL_NV_gpu_shader5 enabled as its Vulkan extensions, when {@code types} serve it. */
    public static String forVulkan(String source, SizedTypes types) {
        if (source == null || !types.all() || !source.contains(NVIDIA)) return source;
        Matcher line = EXTENSION.matcher(source);
        StringBuilder result = new StringBuilder();
        while (line.find()) {
            List<String> names = line.group(2).equals(NVIDIA) ? VULKAN : List.of(line.group(2));
            List<String> lines = names.stream().map(name -> line.group(1) + name + line.group(3)).toList();
            line.appendReplacement(result, Matcher.quoteReplacement(String.join("\n", lines)));
        }
        line.appendTail(result);
        return result.toString();
    }
}
