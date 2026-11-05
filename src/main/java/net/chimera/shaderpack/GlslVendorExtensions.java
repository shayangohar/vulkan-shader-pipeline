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
     * GL_AMD_shader_trinary_minmax as min and max without the device extension, 16-bit stage
     * inputs and outputs as their 32-bit types without storageInputOutput16 ({@link #widenInterfaces}),
     * and a sampler chosen by ?: moved out of the call it feeds, as GL drivers accept it (OpaqueSelections).
     */
    public static String forVulkan(String source, PackDevice device) {
        String result = forVulkan(OpaqueSelections.hoist(hoistExtensions(source)), device.sizedTypes(),
                device.trinaryMinmax());
        return device.features().contains("storageInputOutput16") ? result : widenInterfaces(result);
    }

    /** A {@code #extension} or {@code #version} directive, as a line's text outside comments begins. */
    private static final Pattern DIRECTIVE = Pattern.compile("^[ \\t]*#[ \\t]*(extension|version)\\b");

    /**
     * {@code source} with its {@code #extension} lines moved up to follow its {@code #version}
     * line, when code comes before one of them, and unchanged otherwise. Chimera declares a
     * stage's outputs and uniforms ahead of the pack's source, and RenderPearl's AMD path declares
     * them f16vec3, which is no type before its GL_AMD_gpu_shader_half_float directive. A directive
     * in a comment is no directive and stays.
     */
    static String hoistExtensions(String source) {
        if (source == null || !source.contains("extension")) return source;
        List<int[]> extensions = new ArrayList<>();
        int versionEnd = 0;
        boolean code = false;
        boolean misplaced = false;
        boolean[] inComment = {false};
        for (int start = 0; start < source.length(); ) {
            int newline = source.indexOf('\n', start);
            int end = newline < 0 ? source.length() : newline + 1;
            String visible = outsideComments(source.substring(start, end), inComment);
            Matcher directive = DIRECTIVE.matcher(visible);
            if (directive.find()) {
                if (directive.group(1).equals("version")) {
                    versionEnd = end;
                } else {
                    extensions.add(new int[] {start, end});
                    misplaced |= code;
                }
            } else if (!visible.isBlank() && !visible.strip().startsWith("#")) {
                code = true;
            }
            start = end;
        }
        if (!misplaced) return source;
        StringBuilder hoisted = new StringBuilder();
        StringBuilder rest = new StringBuilder(source.substring(0, versionEnd));
        int at = versionEnd;
        for (int[] line : extensions) {
            // One above the version line stays, as the compiler would read it.
            if (line[0] < versionEnd) continue;
            hoisted.append(source, line[0], line[1]).append(source.charAt(line[1] - 1) == '\n' ? "" : "\n");
            rest.append(source, at, line[0]);
            at = line[1];
        }
        rest.append(source.substring(at));
        boolean newline = versionEnd == 0 || source.charAt(versionEnd - 1) == '\n';
        return rest.insert(versionEnd, (newline ? "" : "\n") + hoisted).toString();
    }

    /** {@code line} with its comments blanked; {@code inComment[0]} carries a block comment on to the next line. */
    private static String outsideComments(String line, boolean[] inComment) {
        StringBuilder visible = new StringBuilder(line.length());
        for (int index = 0; index < line.length(); index++) {
            if (inComment[0]) {
                if (line.startsWith("*/", index)) {
                    inComment[0] = false;
                    index++;
                }
                visible.append(' ');
            } else if (line.startsWith("/*", index)) {
                inComment[0] = true;
                index++;
                visible.append(' ');
            } else if (line.startsWith("//", index)) {
                break;
            } else {
                visible.append(line.charAt(index));
            }
        }
        return visible.toString();
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

    /** The 32-bit type a 16-bit stage interface type widens to, or null for another type. */
    static String widenedType(String type) {
        return WIDENED.get(type);
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
