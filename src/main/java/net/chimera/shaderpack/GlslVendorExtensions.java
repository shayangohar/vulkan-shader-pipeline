package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    private static final String TRINARY = "GL_AMD_shader_trinary_minmax";
    /**
     * GL_AMD_shader_trinary_minmax's functions as the extension defines them, for a device without
     * VK_AMD_shader_trinary_minmax, whose SPIR-V crashed NVIDIA's driver (PackDevice.missing).
     * RenderPearl requires the extension on any GPU it does not know to be NVIDIA's, and defines
     * the same two macros itself when it does not.
     */
    private static final List<String> TRINARY_BUILT_INS = List.of(
            "#define min3(a, b, c) min(a, min(b, c))",
            "#define max3(a, b, c) max(a, max(b, c))",
            "#define mid3(a, b, c) max(min(a, b), min(max(a, b), c))");

    /** {@code source} with GL_NV_gpu_shader5 enabled as its Vulkan extensions, when {@code types} serve it. */
    public static String forVulkan(String source, SizedTypes types) {
        return forVulkan(source, types, true);
    }

    /**
     * {@link #forVulkan(String, SizedTypes)}, and GL_AMD_shader_trinary_minmax's functions written
     * with min and max in place of its directive when the device lacks it ({@code trinaryMinmax}).
     */
    public static String forVulkan(String source, SizedTypes types, boolean trinaryMinmax) {
        if (source == null) return null;
        boolean nvidia = types.all() && source.contains(NVIDIA);
        boolean trinary = !trinaryMinmax && source.contains(TRINARY);
        if (!nvidia && !trinary) return source;
        Matcher line = EXTENSION.matcher(source);
        StringBuilder result = new StringBuilder();
        while (line.find()) {
            String name = line.group(2);
            List<String> lines;
            if (nvidia && name.equals(NVIDIA)) {
                lines = VULKAN.stream().map(vulkan -> line.group(1) + vulkan + line.group(3)).toList();
            } else if (trinary && name.equals(TRINARY)) {
                lines = TRINARY_BUILT_INS;
            } else {
                lines = List.of(line.group());
            }
            line.appendReplacement(result, Matcher.quoteReplacement(String.join("\n", lines)));
        }
        line.appendTail(result);
        return result.toString();
    }

    /**
     * {@code source} as the {@code device} compiles it: GL_NV_gpu_shader5 as its Vulkan extensions,
     * GL_AMD_shader_trinary_minmax as min and max without the device extension, and 16-bit stage
     * inputs and outputs as their 32-bit types without storageInputOutput16 ({@link #widenInterfaces}).
     */
    public static String forVulkan(String source, PackDevice device) {
        String result = forVulkan(source, device.sizedTypes(), device.trinaryMinmax());
        return device.features().contains("storageInputOutput16") ? result : widenInterfaces(result);
    }

    /**
     * {@code source} with its 16-bit stage inputs and outputs declared as their 32-bit types. A
     * Vulkan stage interface of a 16-bit type needs storageInputOutput16, which NVIDIA's GPUs lack,
     * while their GL driver takes RenderPearl's {@code out f16vec4 colortex1}. A 16-bit value written
     * to the output widens implicitly, and the target stores it in its own format; an input reads
     * as the wider type.
     */
    static String widenInterfaces(String source) {
        if (source == null || WIDENED.keySet().stream().noneMatch(source::contains)) return source;
        GlslTree tree;
        try {
            tree = GlslTree.parse(source);
        } catch (GlslParser.ParseFailure failure) {
            return source;
        }
        List<GlslTree.Edit> edits = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (external instanceof GlslTree.Declaration declaration
                    && stageInterface(tree, declaration.qualifiers())) {
                widen(tree, declaration, edits);
            } else if (external instanceof GlslTree.InterfaceBlock block
                    && stageInterface(tree, block.qualifiers())) {
                block.members().forEach(member -> widen(tree, member, edits));
            }
        }
        return edits.isEmpty() ? source : tree.print(edits);
    }

    /** The 16-bit types a stage interface can declare, and the 32-bit type each widens to. */
    private static final Map<String, String> WIDENED = widened();

    private static Map<String, String> widened() {
        Map<String, String> result = new HashMap<>(Map.of("float16_t", "float", "int16_t", "int",
                "uint16_t", "uint"));
        for (int size = 2; size <= 4; size++) {
            result.put("f16vec" + size, "vec" + size);
            result.put("i16vec" + size, "ivec" + size);
            result.put("u16vec" + size, "uvec" + size);
            result.put("f16mat" + size, "mat" + size);
            for (int rows = 2; rows <= 4; rows++) result.put("f16mat" + size + "x" + rows, "mat" + size + "x" + rows);
        }
        return Map.copyOf(result);
    }

    private static boolean stageInterface(GlslTree tree, List<GlslTree.Qualifier> qualifiers) {
        return qualifiers.stream().map(qualifier -> tree.text(qualifier.keyword()))
                .anyMatch(keyword -> keyword.equals("in") || keyword.equals("out") || keyword.equals("varying")
                        || keyword.equals("attribute"));
    }

    private static void widen(GlslTree tree, GlslTree.Declaration declaration, List<GlslTree.Edit> edits) {
        if (declaration.type() == null || declaration.type().struct() != null) return;
        int name = declaration.type().name();
        String wide = WIDENED.get(tree.text(name));
        if (wide != null) edits.add(new GlslTree.Edit(new GlslTree.Range(name, name + 1), wide));
    }
}
