package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one deterministic interpretation of a legacy post program's render
 * targets. The same plan is used by probing, conversion, pipeline creation,
 * and the runtime post chain.
 */
public final class PostTargetPlan {
    /** Highest logical colortex index: Iris exposes colortex0 through colortex15. */
    public static final int MAX_TARGET = 15;
    /**
     * A colortex target the pack declares no format for: Iris's RenderTargetSettings starts at
     * InternalTextureFormat.RGBA, which GL allocates as RGBA8 (VK_FORMAT_R8G8B8A8_UNORM), so it
     * clamps to [0, 1] as under Iris.
     */
    public static final int DEFAULT_FORMAT = 37;
    /**
     * The numbered pass families in the order Iris runs them: the setup computes, then its composite
     * renderers (IrisRenderingPipeline), shadowcomp running after the shadow pass, before prepare.
     */
    private static final List<String> PASS_FAMILIES =
            List.of("setup", "begin", "shadowcomp", "prepare", "deferred", "composite");

    private static final Pattern TARGET_COMMENT = Pattern.compile(
            "(?is)/\\*\\s*(DRAWBUFFERS|RENDERTARGETS)\\s*:\\s*([^*]*?)\\*/");
    /** The route Iris gives a shadow-pass source with no target directive: shadowcolor0 and shadowcolor1. */
    private static final String SHADOW_DEFAULT_TARGETS = "/* DRAWBUFFERS:01 */";
    private static final Pattern DIRECTIVE_WORD = Pattern.compile(
            "(?i)\\b(?:DRAWBUFFERS|RENDERTARGETS)\\b");
    private static final Pattern FRAG_DATA = Pattern.compile(
            "gl_FragData\\s*\\[\\s*(\\d+)\\s*\\]");
    /** RenderPearl's NVIDIA path writes f16vec3 shadow colours; GlslVendorExtensions widens them where it must. */
    private static final Pattern OUTPUT_TYPE = Pattern.compile(
            "float|int|uint|[iu]?vec[234]|float16_t|u?int16_t|[fiu]16vec[234]");
    private static final Pattern MIPMAP_DIRECTIVE_NAME = Pattern.compile(
            "(colortex\\d+|gcolor|gdepth|gnormal|composite|gaux[1-4])MipmapEnabled");

    /** One modern fragment output: its declaration's source range, type, name, location and component. */
    public record ModernOutput(int start, int end, String type, String name, int location, int component) {}

    /**
     * Modern fragment outputs as GL links them for Iris: an explicit
     * {@code layout(location = N)} is kept, and each unlocated output takes
     * the lowest location still free, in declaration order.
     */
    public static List<ModernOutput> modernOutputs(String source) {
        String text = source == null ? "" : source;
        GlslTree tree = GlslDeclarations.parseOrNull(text);
        if (tree == null) return List.of();
        List<GlslDeclarations.Variable> declared = new ArrayList<>();
        Set<Integer> explicit = new HashSet<>();
        for (GlslDeclarations.Variable output : GlslDeclarations.variables(tree)) {
            List<String> qualifiers = output.qualifiers();
            int start = tree.offset(output.declaration().range().start());
            // A gl_ name is a built-in redeclared (layout(depth_greater) out float gl_FragDepth).
            if ((qualifiers.equals(List.of("out")) || qualifiers.equals(List.of("layout", "out")))
                    && !output.name().startsWith("gl_")
                    && OUTPUT_TYPE.matcher(output.type()).matches() && output.plain()
                    && GlslDeclarations.indentationStart(text, start) >= 0) {
                declared.add(output);
                Integer location = GlslDeclarations.location(tree, output.declaration());
                if (location != null) explicit.add(location);
            }
        }
        List<ModernOutput> outputs = new ArrayList<>();
        int next = 0;
        for (GlslDeclarations.Variable output : declared) {
            Integer location = GlslDeclarations.location(tree, output.declaration());
            if (location == null) {
                while (explicit.contains(next)) next++;
                location = next++;
            }
            int start = tree.offset(output.declaration().range().start());
            outputs.add(new ModernOutput(GlslDeclarations.indentationStart(text, start),
                    tree.offset(output.declaration().range().end()), output.type(), output.name(), location,
                    GlslDeclarations.component(tree, output.declaration())));
        }
        return List.copyOf(outputs);
    }

    private final String programName;
    private final List<Integer> targetSlots;
    private final List<Integer> outputLocations;
    private final List<Integer> discardedOutputs;
    private final List<Integer> outputFormats;
    private final int[] outputFormatsArray;
    private final Map<Integer, String> outputTypes;
    private final Map<Integer, Integer> outputComponents;
    private final List<Integer> mipmappedTargets;
    private final List<String> deviations;
    private final boolean writesNoOutput;

    private PostTargetPlan(
            String programName,
            List<Integer> targetSlots,
            List<Integer> outputLocations,
            List<Integer> discardedOutputs,
            List<Integer> outputFormats,
            Map<Integer, String> outputTypes,
            Map<Integer, Integer> outputComponents,
            List<Integer> mipmappedTargets,
            List<String> deviations,
            boolean writesNoOutput
    ) {
        this.writesNoOutput = writesNoOutput;
        this.programName = programName;
        this.targetSlots = List.copyOf(targetSlots);
        this.outputLocations = List.copyOf(outputLocations);
        this.discardedOutputs = List.copyOf(discardedOutputs);
        this.outputFormats = List.copyOf(outputFormats);
        this.outputFormatsArray = outputFormats.stream().mapToInt(Integer::intValue).toArray();
        this.outputTypes = Map.copyOf(outputTypes);
        this.outputComponents = Map.copyOf(outputComponents);
        this.mipmappedTargets = List.copyOf(mipmappedTargets);
        this.deviations = deviations.stream().distinct().sorted().toList();
    }

    public static ParseResult parse(String programName, String source, Map<Integer, Integer> formats) {
        String text = source == null ? "" : source;
        Map<Integer, Integer> safeFormats = formats == null ? Map.of() : formats;
        List<String> deviations = new ArrayList<>();
        List<List<Integer>> directives = parseDirectives(text, deviations);

        List<Integer> targetSlots = selectDirective(directives);
        Set<Integer> outputSet = legacyOutputs(text, deviations);
        Map<Integer, String> outputTypes = new TreeMap<>();
        Map<Integer, Integer> outputComponents = new TreeMap<>();
        for (ModernOutput output : modernOutputs(text)) {
            if (outputTypes.put(output.location(), output.type()) != null) {
                deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
            }
            if (output.component() != 0) outputComponents.put(output.location(), output.component());
            outputSet.add(output.location());
        }

        for (int target : targetSlots) {
            if (target < 0 || target > MAX_TARGET) {
                deviations.add("POST_TARGET_INDEX_UNSUPPORTED:" + target);
            }
        }
        // GL discards a fragment output with no draw buffer at its location, so an output
        // past the route is written to nothing (Photon declares a second gbuffer output it
        // routes only when normal or specular mapping is on).
        List<Integer> discardedOutputs = outputSet.stream()
                .filter(output -> output >= targetSlots.size()).toList();
        for (int output : discardedOutputs) {
            deviations.add("FRAGMENT_OUTPUT_DISCARDED:" + output);
        }
        if (programName.equals("final") && targetSlots.size() != 1) {
            deviations.add("FINAL_MRT_UNSUPPORTED");
        }

        List<Integer> outputLocations = outputSet.stream()
                .filter(output -> output < targetSlots.size()).toList();
        List<Integer> outputFormats = targetSlots.stream()
                .map(slot -> safeFormats.getOrDefault(slot, DEFAULT_FORMAT))
                .toList();
        PostTargetPlan plan = new PostTargetPlan(
                programName, targetSlots, outputLocations, discardedOutputs, outputFormats,
                outputTypes, outputComponents, parseMipmappedTargets(text), deviations,
                writesNoOutput(text, outputSet));
        return new ParseResult(plan, plan.deviations());
    }

    /**
     * Whether the shader certainly writes no output: it names neither gl_FragData nor
     * gl_FragColor, and parses with no output variable. Rethinking Voxels' prepare1 and prepare2
     * only discard, and work through image stores.
     */
    private static boolean writesNoOutput(String text, Set<Integer> outputs) {
        return outputs.isEmpty() && !text.contains("gl_FragData") && !text.contains("gl_FragColor")
                && GlslDeclarations.parseOrNull(text) != null;
    }

    /**
     * A shadow source as Iris draws it when its target directive names a shadowcolor at or past the
     * pass's {@code count}: ShadowRenderTargets.createColorFramebuffer then draws to {0, 1}, so
     * output 0 writes shadowcolor0, output 1 shadowcolor1, and a later output is discarded.
     */
    static String withShadowTargetsWithin(String source, int count, List<String> deviations) {
        if (source == null || !TARGET_COMMENT.matcher(source).find()) return source;
        List<Integer> outside = parse("shadow", source).plan().targetSlots().stream()
                .filter(target -> target < 0 || target >= count)
                .toList();
        if (outside.isEmpty()) return source;
        outside.forEach(target -> deviations.add("SHADOW_DRAWBUFFERS_RESET:" + target));
        return TARGET_COMMENT.matcher(source).replaceAll(SHADOW_DEFAULT_TARGETS);
    }

    /**
     * The routes the target comments declare, as Iris reads them ({@code /* DRAWBUFFERS:01 *}{@code /},
     * {@code /* RENDERTARGETS: 0,2 *}{@code /}). A malformed directive, or a directive word that no
     * directive parses from, is recorded and declares no route.
     */
    private static List<List<Integer>> parseDirectives(String text, List<String> deviations) {
        List<List<Integer>> directives = new ArrayList<>();
        boolean directiveSeen = false;

        Matcher comment = TARGET_COMMENT.matcher(text);
        while (comment.find()) {
            addDirective(parseCommentTargets(comment.group(1), comment.group(2), deviations), directives,
                    deviations);
            directiveSeen = true;
        }

        if (DIRECTIVE_WORD.matcher(text).find() && !directiveSeen) {
            deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
        }
        return directives;
    }

    private static void addDirective(List<Integer> parsed, List<List<Integer>> directives, List<String> deviations) {
        if (parsed.isEmpty()) {
            deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
        } else {
            directives.add(parsed);
        }
    }

    /** The output locations gl_FragData and gl_FragColor write. */
    private static Set<Integer> legacyOutputs(String text, List<String> deviations) {
        Set<Integer> outputSet = new TreeSet<>();
        Matcher fragData = FRAG_DATA.matcher(text);
        while (fragData.find()) {
            try {
                outputSet.add(Integer.parseInt(fragData.group(1)));
            } catch (NumberFormatException e) {
                deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
            }
        }
        if (text.matches("(?s).*\\bgl_FragColor\\b.*")) {
            outputSet.add(0);
        }
        return outputSet;
    }

    /**
     * Iris ProgramDirectives: this program's fragment const directives, in
     * source order, so a later {@code false} removes an earlier {@code true}.
     */
    private static List<Integer> parseMipmappedTargets(String source) {
        Set<Integer> targets = new TreeSet<>();
        // ConstDirectiveParser reads lines, so a directive inside a block comment counts (Photon's
        // d4_deferred_shading declares its mipmapped targets that way).
        for (IrisConstDirectives.Directive directive : IrisConstDirectives.find(source)) {
            Matcher name = MIPMAP_DIRECTIVE_NAME.matcher(directive.name());
            Integer target = name.matches() ? PackResourcePlan.targetIndex(name.group(1)) : null;
            if (!directive.type().equals("bool") || target == null || target < 0 || target > MAX_TARGET) continue;
            if (directive.value().equals("true")) {
                targets.add(target);
            } else if (directive.value().equals("false")) {
                targets.remove(target);
            }
        }
        return List.copyOf(targets);
    }

    public static ParseResult parse(String programName, String source) {
        return parse(programName, source, Map.of());
    }

    public static boolean isPostProgramName(String name) {
        return name != null && name.matches("(?:prepare|deferred|composite)\\d*|final");
    }

    /**
     * A fullscreen pass converted with the post converter: a post program over the colour targets, or
     * a shadowcomp pass over the shadowcolor targets (PackShadowComposites).
     */
    public static boolean isFullscreenProgramName(String name) {
        return isPostProgramName(name) || PassComputePlan.isShadowcomp(name);
    }

    /**
     * Whether a program's target indices name shadowcolor targets rather than colortex ones: a
     * shadowcomp pass, or a program that draws in the shadow pass (shadow and the shadow lanes).
     */
    public static boolean drawsShadowTargets(String name) {
        return PassComputePlan.isShadowcomp(name) || FamilyAdapterRegistry.isShadowFamily(name);
    }

    /**
     * A fullscreen pass's route: a post program's over the colour targets in their formats, a
     * shadowcomp pass's over the shadowcolor targets in theirs; null for any other program.
     */
    static PostTargetPlan forFullscreenProgram(String name, String fragment, PackConfig.PackConfigData config) {
        if (isPostProgramName(name)) {
            return parse(name, fragment, config == null ? Map.of() : config.colortexFormats()).plan();
        }
        return PassComputePlan.isShadowcomp(name) ? parseShadowcomp(name, fragment, config).plan() : null;
    }

    /** A shadowcomp pass's route over the shadowcolor targets (parseShadow), in the pack's shadowcolor formats. */
    static ParseResult parseShadowcomp(String programName, String source, PackConfig.PackConfigData config) {
        List<Integer> shadowFormats = config == null ? List.of() : config.shadowSettings().colorAttachmentFormats();
        Map<Integer, Integer> formats = new TreeMap<>();
        for (int index = 0; index < shadowFormats.size(); index++) formats.put(index, shadowFormats.get(index));
        return parseShadow(programName, source, formats);
    }

    /**
     * A shadow-pass source's route over the shadowcolor targets: its directive, or with none {0, 1}.
     * Iris draws a shadow program or a shadowcomp pass whose draw buffers are unknown to both
     * (IrisRenderingPipeline.createShadowShader, SodiumPrograms.createFramebuffer,
     * ShadowCompositeRenderer), where a colour program's default is {0}. Kappa's shadow program
     * declares no directive and writes its material ids to shadowcolor1 for its shadowcomp to read.
     */
    static ParseResult parseShadow(String programName, String source, Map<Integer, Integer> formats) {
        String text = source == null ? "" : source;
        return parse(programName,
                TARGET_COMMENT.matcher(text).find() ? text : text + "\n" + SHADOW_DEFAULT_TARGETS + "\n", formats);
    }

    public static Comparator<String> programComparator() {
        return Comparator.comparingInt(PostTargetPlan::familyRank)
                .thenComparingInt(PostTargetPlan::numericSuffix)
                .thenComparing(Comparator.naturalOrder());
    }

    /** Final, and any other name, after them. */
    private static int familyRank(String name) {
        for (int rank = 0; rank < PASS_FAMILIES.size(); rank++) {
            if (name.startsWith(PASS_FAMILIES.get(rank))) return rank;
        }
        return PASS_FAMILIES.size();
    }

    private static int numericSuffix(String name) {
        int rank = familyRank(name);
        int index = rank < PASS_FAMILIES.size() ? PASS_FAMILIES.get(rank).length() : name.length();
        if (index == name.length()) {
            return 0;
        }
        try {
            return Integer.parseInt(name.substring(index));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Selects the active output route from the surviving directives: the last one, whatever the
     * earlier ones say, as Iris takes the last DRAWBUFFERS or RENDERTARGETS comment
     * (CommentDirectiveParser, ProgramDirectives). The analyzed source is already branch-resolved.
     * Ebin's DEBUG exit (lib/Exit.glsl) writes DRAWBUFFERS:3 or :0 after the program's own route.
     * Malformed parses never proxy for a real route.
     */
    private static List<Integer> selectDirective(List<List<Integer>> directives) {
        if (directives == null || directives.isEmpty()) {
            return List.of(0);
        }
        List<Integer> selected = directives.get(directives.size() - 1);
        return selected == null || selected.isEmpty() ? List.of(0) : selected;
    }

    private static List<Integer> parseDigits(String digits, List<String> deviations) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < digits.length(); i++) {
            result.add(digits.charAt(i) - '0');
        }
        validateDuplicates(result, deviations);
        return result;
    }

    /** DRAWBUFFERS contains digits; RENDERTARGETS contains comma-separated integers. */
    static List<Integer> parseCommentTargets(String kind, String body, List<String> deviations) {
        String trimmed = body.trim();
        if (trimmed.isEmpty() || !trimmed.matches("[0-9,\\s]+")) {
            deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
            return List.of();
        }
        List<Integer> result = new ArrayList<>();
        if (kind.equalsIgnoreCase("DRAWBUFFERS")) {
            if (trimmed.indexOf(',') >= 0) {
                deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
                return List.of();
            }
            return parseDigits(trimmed.replaceAll("\\s+", ""), deviations);
        }
        for (String token : trimmed.split(",", -1)) {
            String value = token.trim();
            if (!value.matches("\\d+")) {
                deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
                continue;
            }
            try {
                result.add(Integer.parseInt(value));
            } catch (NumberFormatException e) {
                deviations.add("POST_TARGET_DIRECTIVE_MALFORMED");
            }
        }
        validateDuplicates(result, deviations);
        return result;
    }

    private static void validateDuplicates(List<Integer> values, List<String> deviations) {
        Set<Integer> seen = new HashSet<>();
        for (int value : values) {
            if (!seen.add(value)) {
                deviations.add("POST_TARGET_DIRECTIVE_DUPLICATE:" + value);
            }
        }
    }

    public String programName() {
        return programName;
    }

    /** Logical colortex slot for each fragment output location. */
    public List<Integer> targetSlots() {
        return targetSlots;
    }

    /** Fragment output locations used by the source. */
    public List<Integer> outputLocations() {
        return outputLocations;
    }

    /**
     * Whether the source certainly writes no output, so every directive target is left unwritten
     * (PackTargetGraphPlan.unwrittenTargets). An empty {@link #outputLocations()} alone may be a
     * write the scan did not see.
     */
    public boolean writesNoOutput() {
        return writesNoOutput;
    }

    /** Output locations past the route: written, and discarded as GL discards them. */
    public List<Integer> discardedOutputs() {
        return discardedOutputs;
    }

    /** Vulkan format for each output location, in output location order. */
    public List<Integer> outputFormats() {
        return outputFormats;
    }

    /** Internal render-thread view. Callers must not modify the returned array. */
    public int[] outputFormatsArray() {
        return outputFormatsArray;
    }

    /** The declared GLSL type of an output location: a modern output's own type, else vec4. */
    public String outputType(int outputLocation) {
        return outputTypes.getOrDefault(outputLocation, "vec4");
    }

    /**
     * The first component an output location's value fills: a modern output's layout component,
     * else 0. RenderPearl writes its deferred-ignore flag to colortex2.y as a uint at component 1.
     */
    public int outputComponent(int outputLocation) {
        return outputComponents.getOrDefault(outputLocation, 0);
    }

    /** Targets whose mip chain Iris regenerates before this program runs. */
    public List<Integer> mipmappedTargets() {
        return mipmappedTargets;
    }

    public List<String> deviations() {
        return deviations;
    }

    public boolean executable() {
        return deviations.stream().noneMatch(value -> value.startsWith("POST_")
                || value.startsWith("FINAL_MRT_"));
    }

    public boolean requiresMrt() {
        return targetSlots.size() > 1;
    }

    public boolean isFinal() {
        return programName.equals("final");
    }

    public int targetForOutput(int outputLocation) {
        return targetSlots.get(outputLocation);
    }

    public record ParseResult(PostTargetPlan plan, List<String> deviations) {
        public ParseResult {
            deviations = deviations.stream().distinct().sorted().toList();
        }

        public boolean executable() {
            return plan.executable();
        }
    }
}
