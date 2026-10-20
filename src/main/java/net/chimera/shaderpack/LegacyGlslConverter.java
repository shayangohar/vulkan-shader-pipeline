package net.chimera.shaderpack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.chimera.shaderpack.GlslLexer.stripComments;

/**
 * The rewrites every family's converter shares to turn legacy OptiFine-style GLSL into Vulkan
 * GLSL 460: source preparation with includes inlined, varying declarations with explicit
 * locations, injected pack constants, texture calls, and the pruning of unused declarations.
 * The families' converters are TerrainVertexBridge, EntityVertexBridge, HostLayoutVertexBridge,
 * PostVertexBridge and FragmentConverter.
 */
public final class LegacyGlslConverter {
    static final Pattern VERSION_LINE = Pattern.compile("(?m)^\\s*#version\\s+\\S+.*$");
    static final Pattern KNOWN_LEGACY_EXTENSIONS = Pattern.compile(
            "(?im)^\\s*#extension\\s+GL_ARB_shader_texture_lod\\s*:\\s*(?:enable|require|disable)\\s*$\\r?\\n?");

    // What follows a rewritten declaration on its line, as each rewrite's span has always ended: the
    // tree finds the declarations, and these match only the trivia after one.
    private static final Pattern TAIL_TO_LINE_END = Pattern.compile("\\s*(?://.*)?$", Pattern.MULTILINE);
    private static final Pattern TAIL_PRECISION = Pattern.compile("\\s*$\\r?\\n?", Pattern.MULTILINE);
    private static final Pattern TAIL_UNIFORM = Pattern.compile("[ \\t]*(?://[^\\r\\n]*)?\\s*$", Pattern.MULTILINE);
    private static final Pattern TAIL_SAMPLER = Pattern.compile("[ \\t]*(?://[^\\n]*)?$", Pattern.MULTILINE);

    private static final Set<String> INTERPOLATION = Set.of("flat", "noperspective", "smooth", "centroid", "sample");
    private static final Set<String> INTERFACE_STORAGE = Set.of("in", "out", "varying");
    private static final Set<String> PRECISIONS = Set.of("lowp", "mediump", "highp");
    private static final Set<String> LIST_TYPES = Set.of("float", "int", "vec2", "vec3", "vec4");
    private static final Set<String> CONSTANT_TYPES = Set.of("int", "float", "bool", "vec4");
    private static final Pattern VALUE_TYPE = Pattern.compile("bool|int|float|vec[234]|ivec[234]|mat[234]");
    private static final Pattern OPAQUE_TYPE = Pattern.compile("[iu]?sampler\\w*|u?i?image\\w*");

    /** Pack metadata declarations consumed by PackConfig, not executable GLSL. */
    private static final Pattern CONSUMED_CONST_NAME = Pattern.compile(
            "colortex\\d+Format|gaux\\d+Format|colortex\\d+(?:Clear|ClearColor|MipmapEnabled)"
                    + "|shadowMapResolution|shadowDistance|shadowMapDistance|shadowMapSize|shadowMapFov"
                    + "|shadowDistanceRenderMul|sunPathRotation|sunPathOffset");
    private static final Pattern CONSUMED_CONST_VALUE = Pattern.compile("[A-Za-z0-9+_.(), /-]+");
    private static final Pattern METADATA_CONST_NAME = Pattern.compile(
            "(?:colortex|gaux)\\d+(?:Format|Clear|ClearColor|MipmapEnabled)");

    /** A rewritten declaration's characters {@code [start, end)}, its blank lines and trailing trivia included. */
    private record Span(int start, int end, GlslDeclarations.Variable variable) {
        String name() {
            return variable.name();
        }
    }

    /**
     * The spans of the file-scope declarations {@code test} accepts that start a line, each ending
     * where {@code tail} matches after it; a declaration the tail does not fit is skipped.
     */
    private static List<Span> lineSpans(String source, GlslTree tree, Pattern tail,
                                        Predicate<GlslDeclarations.Variable> test) {
        List<Span> spans = new ArrayList<>();
        int previousEnd = 0;
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (!test.test(variable)) continue;
            int start = GlslDeclarations.lineStartBefore(source, tree.offset(variable.declaration().range().start()),
                    previousEnd);
            int end = GlslDeclarations.tailEnd(source, tree.offset(variable.declaration().range().end()), tail);
            if (start >= 0 && end >= 0) {
                spans.add(new Span(start, end, variable));
                previousEnd = Math.max(previousEnd, end);
            }
        }
        return spans;
    }

    private static String without(String source, List<Span> spans) {
        return GlslDeclarations.apply(source, spans.stream()
                .map(span -> new GlslDeclarations.TextEdit(span.start(), span.end(), "")).toList());
    }

    /**
     * Removes consumed metadata constants unless something reads them
     * without a replacement. A referenced constant stays when no pack
     * constant will be injected for it (BSL's shadowMapBias initializer
     * reading shadowDistance); it goes when injection supplies the value,
     * and dead ones always go.
     */
    static String stripUnusedConsumedConsts(
            String source, Map<String, String> packConstants) {
        if (source == null || source.isBlank()) return source;
        GlslTree tree = GlslTree.parse(source);
        List<Span> consumed = lineSpans(source, tree, TAIL_TO_LINE_END, variable -> {
            GlslTree.Range value = variable.declarator().initializer();
            return variable.declaration().declarators().size() == 1 && value != null
                    && variable.qualifiers().stream().allMatch("const"::equals)
                    && CONSTANT_TYPES.contains(variable.type()) && variable.declarator().arrays().isEmpty()
                    && CONSUMED_CONST_NAME.matcher(variable.name()).matches()
                    && CONSUMED_CONST_VALUE.matcher(tree.text(value)).matches();
        });
        String checkSource = without(source, consumed);
        return without(source, consumed.stream().filter(span -> {
            boolean injected = packConstants != null && packConstants.containsKey(span.name());
            return !GlslTokenRewriter.containsIdentifier(checkSource, span.name()) || injected;
        }).toList());
    }

    /** The source without its {@code precision lowp|mediump|highp float|int;} statements. */
    static String withoutPrecisionStatements(String source) {
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.TextEdit> removals = new ArrayList<>();
        int previousEnd = 0;
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Declaration declaration) || !isPrecisionStatement(tree, declaration)) {
                continue;
            }
            int start = GlslDeclarations.lineStartBefore(source, tree.offset(declaration.range().start()),
                    previousEnd);
            int end = GlslDeclarations.tailEnd(source, tree.offset(declaration.range().end()), TAIL_PRECISION);
            if (start >= 0 && end >= 0) {
                removals.add(new GlslDeclarations.TextEdit(start, end, ""));
                previousEnd = end;
            }
        }
        return GlslDeclarations.apply(source, removals);
    }

    private static boolean isPrecisionStatement(GlslTree tree, GlslTree.Declaration declaration) {
        if (declaration.type() == null || !declaration.declarators().isEmpty()
                || declaration.qualifiers().size() != 2) {
            return false;
        }
        String type = tree.text(declaration.type().range());
        return tree.text(declaration.qualifiers().get(0).keyword()).equals("precision")
                && PRECISIONS.contains(tree.text(declaration.qualifiers().get(1).keyword()))
                && (type.equals("float") || type.equals("int"));
    }

    /**
     * An interface declaration's {@code layout(...)} goes with the space after it, and its
     * interpolation and storage qualifiers stay. Only a layout written on one line, on a line of
     * its own declaration's, is removed.
     */
    static String withoutInterfaceLayouts(String source) {
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.TextEdit> removals = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            List<GlslTree.Qualifier> qualifiers = switch (external) {
                case GlslTree.Declaration declaration -> declaration.qualifiers();
                case GlslTree.InterfaceBlock block -> block.qualifiers();
                default -> List.of();
            };
            if (qualifiers.size() < 2 || !tree.text(qualifiers.get(0).keyword()).equals("layout")
                    || tree.text(qualifiers.get(0).range()).matches("(?s).*[\\r\\n].*")
                    || GlslDeclarations.indentationStart(source, tree.offset(external.range().start())) < 0) {
                continue;
            }
            int storage = INTERPOLATION.contains(tree.text(qualifiers.get(1).keyword())) && qualifiers.size() > 2
                    ? 2 : 1;
            if (INTERFACE_STORAGE.contains(tree.text(qualifiers.get(storage).keyword()))) {
                removals.add(new GlslDeclarations.TextEdit(tree.offset(qualifiers.get(0).range().start()),
                        tree.offset(qualifiers.get(1).range().start()), ""));
            }
        }
        return GlslDeclarations.apply(source, removals);
    }

    /** Geometry fragments receive the fixed chimera terrain vertex's outputs by name. */
    private static final Map<String, Integer> GEOMETRY_VARYING_LOCATIONS = Map.of(
            "color", 0,
            "texcoord", 1,
            "lightSpacePos", 5
    );
    private LegacyGlslConverter() {}

    /** Deterministic interface shared by the narrow terrain vertex and fragment bridge. */
    public record TerrainVaryingLayout(Map<String, String> types, Map<String, Integer> locations,
            Map<String, String> qualifiers) {
        public TerrainVaryingLayout(Map<String, String> types, Map<String, Integer> locations) {
            this(types, locations, Map.of());
        }
        public TerrainVaryingLayout {
            types = Collections.unmodifiableMap(new TreeMap<>(types));
            locations = Collections.unmodifiableMap(new TreeMap<>(locations));
            qualifiers = Collections.unmodifiableMap(new TreeMap<>(qualifiers));
        }

        /** Interpolation qualifier carried from the authored declaration, or empty when smooth. */
        public String qualifier(String name) {
            String qualifier = qualifiers.get(name);
            return qualifier == null ? "" : qualifier;
        }

        public int location(String name) {
            Integer location = locations.get(name);
            if (location == null) {
                throw new IllegalArgumentException("terrain varying is not produced by the vertex stage: " + name);
            }
            return location;
        }
    }

    /** Converted terrain vertex source plus the interface used by its fragment stage. */
    public record TerrainVertexConversion(String source, TerrainVaryingLayout layout) {}

    static UniformRegistry.Stage stageOf(boolean geometryStage) {
        return geometryStage ? UniformRegistry.Stage.GEOMETRY : UniformRegistry.Stage.POST;
    }

    static int configIndexOf(
            String name,
            int[] slots,
            UniformRegistry.Stage stage,
            UniformRegistry.ProgramInterface interfacePlan
    ) {
        Integer slotValue = nameToSlot(stage).get(name);
        if (slotValue == null && interfacePlan != null) {
            slotValue = interfacePlan.samplers().stream()
                    .filter(value -> value.name().equals(name))
                    .map(UniformRegistry.SamplerBinding::slot)
                    .findFirst()
                    .orElse(null);
        }
        if (slotValue == null) {
            return -1;
        }
        int slot = slotValue;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == slot) {
                return i;
            }
        }
        return -1;
    }

    /** The fixed sampler-name slots of a stage's family: shadow, entity, translucent or geometry. */
    private static Map<String, Integer> nameToSlot(UniformRegistry.Stage stage) {
        if (stage.shadowPass()) {
            return UniformRegistry.SHADOW_NAME_TO_SLOT;
        }
        return switch (stage) {
            case ENTITY, BLOCK, HAND, PARTICLE -> UniformRegistry.ENTITY_NAME_TO_SLOT;
            case TRANSLUCENT -> UniformRegistry.TRANSLUCENT_NAME_TO_SLOT;
            default -> UniformRegistry.GEOMETRY_NAME_TO_SLOT;
        };
    }

    static String insertAfterFirstLine(String src, String line) {
        int newline = src.indexOf('\n');
        if (newline < 0) {
            return line + "\n" + src;
        }
        return src.substring(0, newline + 1) + line + src.substring(newline + 1);
    }

    /** Names a stage declares at file scope as plain (non-uniform, non-interface) variables. */
    static Set<String> fileScopeGlobals(String source) {
        Set<String> names = new HashSet<>();
        List<GlslLexer.Token> tokens = GlslLexer.lex(stripComments(source));
        int depth = 0;
        int statementStart = 0;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("{") || token.symbol("(")) depth++;
            else if (token.symbol("}") || token.symbol(")")) depth = Math.max(0, depth - 1);
            if (depth != 0) continue;
            if (token.symbol(";") || token.symbol("}") || token.symbol("#")) {
                statementStart = index + 1;
                continue;
            }
            if (namesPlainGlobal(tokens, statementStart, index)) names.add(token.text());
        }
        return names;
    }

    /** Qualifiers that make a file-scope declaration a uniform, interface or storage variable. */
    private static final Set<String> GLOBAL_STORAGE_QUALIFIERS = Set.of(
            "uniform", "in", "out", "varying", "attribute", "buffer", "shared");

    /** Whether the token at {@code index} is the name in a file-scope statement that declares a plain global. */
    private static boolean namesPlainGlobal(List<GlslLexer.Token> tokens, int statementStart, int index) {
        // `type name =|;|[` opening a file-scope statement without uniform/in/out/varying/attribute.
        int next = GlslLexer.nextSignificant(tokens, index);
        if (tokens.get(index).kind() != GlslLexer.Kind.IDENTIFIER || next < 0
                || !(tokens.get(next).symbol("=") || tokens.get(next).symbol(";")
                        || tokens.get(next).symbol("["))) {
            return false;
        }
        int type = GlslLexer.previousSignificant(tokens, index);
        if (type < statementStart || tokens.get(type).kind() != GlslLexer.Kind.IDENTIFIER) return false;
        for (int prior = statementStart; prior < type; prior++) {
            if (GLOBAL_STORAGE_QUALIFIERS.contains(tokens.get(prior).text())) return false;
        }
        return true;
    }

    /** Compatibility adapter for callers that still pass raw source and a path. */
    static String prepareSource(String source, Path sourceFile, boolean vertexStage) {
        String preparedSource = source;
        if (sourceFile != null) {
            Path root = sourceFile.getParent();
            ShaderSourcePreprocessor.Result prepared = ShaderSourcePreprocessor.prepare(
                    root, sourceFile, source,
                    PackEngineDefines.forPack(Map.of()),
                    PackEngineDefines.lockedNames(Set.of(), false, false));
            if (!prepared.successful()) {
                throw new IllegalArgumentException(prepared.deviations().toString());
            }
            preparedSource = prepared.source();
        }
        LegacyShaderNormalizer.Result normalized = LegacyShaderNormalizer.normalize(preparedSource, vertexStage);
        if (!normalized.successful()) {
            throw new IllegalArgumentException(normalized.deviations().toString());
        }
        return normalized.source();
    }

    static String convertVaryings(
            String src,
            boolean geometryStage,
            TerrainVaryingLayout terrainLayout,
            PostVertexBridge.PostVaryingLayout postVaryingLayout,
            UniformRegistry.Stage stage
    ) {
        src = expandModernVaryingLists(src);
        if (!geometryStage) {
            return postVaryingLayout == null
                    ? PostVertexBridge.convertPostVaryings(src)
                    : PostVertexBridge.convertPostVaryings(src, postVaryingLayout);
        }
        if (ENTITY_VARYING_STAGES.contains(stage)) {
            return EntityVertexBridge.replaceEntityVaryings(expandModernVaryingLists(src), terrainLayout, "in", false);
        }
        if (src.contains("#version 460")) {
            return convertModernGeometryVaryings(src, terrainLayout);
        }
        return convertLegacyGeometryVaryings(src, terrainLayout);
    }

    /** Stages whose fragment inputs follow the entity bridge's varying layout. */
    private static final Set<UniformRegistry.Stage> ENTITY_VARYING_STAGES = EnumSet.of(
            UniformRegistry.Stage.ENTITY, UniformRegistry.Stage.BLOCK, UniformRegistry.Stage.HAND,
            UniformRegistry.Stage.PARTICLE, UniformRegistry.Stage.SKY, UniformRegistry.Stage.CLOUD);

    /** Gives a modern geometry fragment's inputs the terrain layout's locations, or the fixed vertex's. */
    private static String convertModernGeometryVaryings(String src, TerrainVaryingLayout terrainLayout) {
        Matcher modern = TerrainVertexBridge.MODERN_TERRAIN_INPUT_DECL.matcher(src);
        StringBuilder modernOut = new StringBuilder();
        int modernLast = 0;
        while (modern.find()) {
            String qualifier = modern.group(1);
            String type = modern.group(2);
            String name = modern.group(3);
            if (PostVertexBridge.unproducedAndUnread(src, modern.start(), modern.end(), name, terrainLayout)) {
                modernOut.append(src, modernLast, modern.start());
                modernLast = modern.end();
                continue;
            }
            int location = terrainLayout == null
                    ? GEOMETRY_VARYING_LOCATIONS.getOrDefault(name, -1)
                    : terrainLayout.location(name);
            if (location < 0) {
                throw new IllegalArgumentException("modern geometry varying has no fixed slot: " + name);
            }
            modernOut.append(src, modernLast, modern.start());
            modernOut.append("layout(location = ").append(location).append(") ");
            if (qualifier != null) {
                modernOut.append(qualifier).append(' ');
            }
            modernOut.append("in ").append(type).append(' ').append(name).append(';');
            modernLast = modern.end();
        }
        modernOut.append(src, modernLast, src.length());
        return modernOut.toString();
    }

    /** Gives a legacy geometry fragment's varyings the terrain layout's locations, or the fixed vertex's. */
    private static String convertLegacyGeometryVaryings(String src, TerrainVaryingLayout terrainLayout) {
        // A legacy varying of any type the vertex layout sized: Sildur's passes varying mat3 tbnMatrix.
        GlslTree tree = GlslTree.parse(src);
        List<Span> varyings = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            int start = GlslDeclarations.lineStartBefore(src, tree.offset(variable.declaration().range().start()));
            if (start >= 0 && variable.qualifiers().equals(List.of("varying")) && variable.plain()
                    && !variable.type().equals("struct")) {
                varyings.add(new Span(start, tree.offset(variable.declaration().range().end()), variable));
            }
        }
        StringBuilder out = new StringBuilder();
        int last = 0;
        for (Span varying : varyings) {
            String type = varying.variable().type();
            String name = varying.name();
            if (PostVertexBridge.unproducedAndUnread(src, varying.start(), varying.end(), name, terrainLayout)) {
                out.append(src, last, varying.start());
                last = varying.end();
                continue;
            }
            int location;
            if (terrainLayout != null) {
                location = terrainLayout.location(name);
            } else {
                Integer slot = GEOMETRY_VARYING_LOCATIONS.get(name);
                if (slot == null) {
                    throw new IllegalArgumentException(
                            "geometry varying '" + name + "' has no fixed-vertex slot (expected one of "
                                    + GEOMETRY_VARYING_LOCATIONS.keySet() + ")");
                }
                location = slot;
            }
            out.append(src, last, varying.start());
            out.append("layout(location = ").append(location).append(") "
                    + "in ").append(type).append(' ').append(name).append(';');
            last = varying.end();
        }
        out.append(src, last, src.length());
        return out.toString();
    }

    static boolean containsIdentifier(String source, String name) {
        return GlslTokenRewriter.containsIdentifier(source, name);
    }

    /** Removes the {@code const} target metadata PackConfig reads; a program that reads one is refused. */
    static String removePackMetadataConstants(String source) {
        GlslTree tree = GlslTree.parse(source);
        List<Span> metadata = lineSpans(source, tree, TAIL_TO_LINE_END, variable ->
                variable.qualifiers().equals(List.of("const")) && CONSTANT_TYPES.contains(variable.type())
                        && variable.declaration().declarators().size() == 1
                        && variable.declarator().arrays().isEmpty() && variable.declarator().initializer() != null
                        && METADATA_CONST_NAME.matcher(variable.name()).matches());
        for (Span declaration : metadata) {
            String withoutDeclaration = source.substring(0, declaration.start())
                    + source.substring(declaration.end());
            if (containsIdentifier(withoutDeclaration, declaration.name())) {
                throw new IllegalArgumentException("pack metadata constant is used: " + declaration.name());
            }
        }
        return without(source, metadata);
    }

    static String injectPackConstants(String source, Map<String, String> packConstants) {
        if (packConstants == null || packConstants.isEmpty()) {
            return source;
        }
        String stripped = stripComments(source);
        StringBuilder declarations = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(packConstants).entrySet()) {
            if (!containsIdentifier(stripped, entry.getKey())
                    || Pattern.compile("(?m)^\\s*#define\\s+" + Pattern.quote(entry.getKey()) + "\\b")
                    .matcher(stripped).find()) {
                continue;
            }
            String type = entry.getKey().equals("shadowMapResolution") ? "int" : "float";
            declarations.append("const ").append(type).append(' ')
                    .append(entry.getKey()).append(" = ").append(entry.getValue()).append(";\n");
        }
        if (declarations.length() == 0) {
            return source;
        }
        return source.matches("(?s)^\\s*#version\\b.*")
                ? insertAfterFirstLine(source, declarations.toString())
                : declarations + source;
    }

    /**
     * Removes value-type uniform declarations the pipeline can already
     * serve or safely ignore: every declarator is either served through
     * the shared pack uniform block (a fragment-list member) or never
     * referenced outside declarations. Initializers do not protect a
     * declaration. Referenced unknowns stay visible so rejection names
     * the first one instead of failing at shaderc.
     */
    static String stripUnusableUniformDeclarations(
            String source, List<UniformRegistry.UniformDeclaration> fragmentUniforms) {
        if (source == null || source.isBlank()) return source;
        Set<String> served = new TreeSet<>();
        if (fragmentUniforms != null) {
            for (UniformRegistry.UniformDeclaration uniform : fragmentUniforms) {
                served.add(uniform.name());
            }
        }
        String withoutServed = without(source, valueUniforms(source).stream()
                .filter(span -> declaratorNames(span).stream().allMatch(name -> name != null && served.contains(name)))
                .toList());
        List<Span> remaining = valueUniforms(withoutServed);
        String checkSource = without(withoutServed, remaining);
        return without(withoutServed, remaining.stream().filter(span -> declaratorNames(span).stream()
                .noneMatch(name -> name != null && GlslTokenRewriter.containsIdentifier(checkSource, name))).toList());
    }

    /** Value-type uniforms on lines of their own: {@code uniform [precision] type names;} and a trailing comment. */
    private static List<Span> valueUniforms(String source) {
        return onePerDeclaration(lineSpans(source, GlslTree.parse(source), TAIL_UNIFORM,
                LegacyGlslConverter::isValueUniform));
    }

    private static boolean isValueUniform(GlslDeclarations.Variable variable) {
        List<String> qualifiers = variable.qualifiers();
        boolean uniform = qualifiers.equals(List.of("uniform")) || qualifiers.size() == 2
                && qualifiers.get(0).equals("uniform") && PRECISIONS.contains(qualifiers.get(1));
        return uniform && VALUE_TYPE.matcher(variable.type()).matches()
                && variable.declaration().type().arrays().isEmpty();
    }

    private static boolean isOpaqueUniform(GlslDeclarations.Variable variable) {
        return variable.qualifiers().equals(List.of("uniform")) && OPAQUE_TYPE.matcher(variable.type()).matches()
                && variable.declaration().type().arrays().isEmpty();
    }

    /** The first span of each declaration: a declarator list is one span. */
    private static List<Span> onePerDeclaration(List<Span> spans) {
        Set<GlslTree.Declaration> seen = new HashSet<>();
        return spans.stream().filter(span -> seen.add(span.variable().declaration())).toList();
    }

    /**
     * Drops functions the vertex main never reaches so dead library code
     * cannot ghost-reference samplers or uniforms into the feature checks.
     * Best-effort: an unanalyzable source keeps its dead code and fails
     * loudly downstream instead of being rewritten on a guess.
     */
    static String pruneUnreachableVertexFunctions(String src) {
        try {
            GlslResourceUsage.Analysis usage = GlslResourceUsage.analyze(src);
            if (!usage.successful()) {
                return src;
            }
            return GlslTokenRewriter.removeUnreachableFunctions(src, usage);
        } catch (RuntimeException e) {
            return src;
        }
    }

    /** Each declarator's name, or null for one with array sizes, which these rewrites leave alone. */
    private static List<String> declaratorNames(Span span) {
        GlslTree tree = span.variable().tree();
        List<String> names = new ArrayList<>();
        for (GlslTree.Declarator declarator : span.variable().declaration().declarators()) {
            names.add(declarator.arrays().isEmpty() ? tree.text(declarator.name()) : null);
        }
        return names;
    }

    /**
     * Removes sampler declarations nothing reads. Vulkan requires
     * layout(binding) on every opaque uniform, so even a dead sampler
     * declaration fails compilation. Referenced samplers fail loudly in
     * rejection instead; layout-qualified lines are never touched.
     */
    static String stripUnreferencedSamplerDeclarations(String source) {
        if (source == null || source.isBlank()) return source;
        GlslTree tree = GlslTree.parse(source);
        // A trailing line comment (Bliss: `uniform sampler2D noisetex;//depth`) still ends it.
        List<Span> unreferenced = new ArrayList<>();
        List<Span> samplers = lineSpans(source, tree, TAIL_SAMPLER, LegacyGlslConverter::isOpaqueUniform);
        for (Span span : onePerDeclaration(samplers)) {
            String checkSource = source.substring(0, span.start()) + source.substring(span.end());
            if (declaratorNames(span).stream()
                    .noneMatch(name -> name != null && GlslTokenRewriter.containsIdentifier(checkSource, name))) {
                unreferenced.add(span);
            }
        }
        return without(source, unreferenced);
    }

    /** {@code statement} as the first line of main's body. */
    static String injectMainPrologue(String source, String statement) {
        GlslTree tree = GlslTree.parse(source);
        GlslTree.Function main = GlslDeclarations.mainDefinition(tree);
        if (main == null) {
            throw new IllegalArgumentException("entity vertex main body is missing");
        }
        int body = tree.offset(main.body().range().start()) + 1;
        return source.substring(0, body) + "\n    " + statement + source.substring(body);
    }

    static String convertTextureCalls(String src) {
        return GlslTokenRewriter.rewriteTextureCalls(GlslTokenRewriter.renameReservedSamplerParameters(src));
    }

    /** Expands comma-separated stage declarations before assigning locations. */
    static String expandModernVaryingLists(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.TextEdit> expansions = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Declaration declaration)) continue;
            String expanded = expandedList(source, tree, declaration);
            if (expanded != null) {
                expansions.add(new GlslDeclarations.TextEdit(
                        GlslDeclarations.indentationStart(source, tree.offset(declaration.range().start())),
                        tree.offset(declaration.range().end()), expanded));
            }
        }
        return GlslDeclarations.apply(source, expansions);
    }

    /**
     * {@code [interpolation] in|out|varying type a, b;} written one declaration a line, each line
     * ending in a line break; null for any other declaration.
     */
    private static String expandedList(String source, GlslTree tree, GlslTree.Declaration declaration) {
        List<String> qualifiers = declaration.qualifiers().stream().map(value -> tree.text(value.keyword())).toList();
        int storage = qualifiers.size() == 2 && INTERPOLATION.contains(qualifiers.get(0)) ? 1 : 0;
        int start = tree.offset(declaration.range().start());
        int indent = GlslDeclarations.indentationStart(source, start);
        if (indent < 0 || qualifiers.size() != storage + 1 || !INTERFACE_STORAGE.contains(qualifiers.get(storage))
                || declaration.type() == null || !LIST_TYPES.contains(tree.text(declaration.type().range()))
                || declaration.declarators().size() < 2 || declaration.declarators().stream()
                .anyMatch(value -> !value.arrays().isEmpty() || value.initializer() != null)) {
            return null;
        }
        String prefix = source.substring(indent, start) + (storage == 1 ? qualifiers.get(0) + " " : "")
                + qualifiers.get(storage) + " " + tree.text(declaration.type().range()) + " ";
        StringBuilder result = new StringBuilder();
        for (GlslTree.Declarator declarator : declaration.declarators()) {
            result.append(prefix).append(tree.text(declarator.name())).append(";\n");
        }
        return result.toString();
    }

    /**
     * Iris's legacy lightmap matrix for gl_TextureMatrix[1] and [2]: the light
     * coordinates arrive raw (0..240, as UV2) and this scale and offset
     * normalize them to the lightmap texel centre, (level + 0.5) / 16.
     */
    static String replaceLightmapTextureMatrices(String source) {
        return source.replaceAll("\\bgl_TextureMatrix\\s*\\[\\s*[12]\\s*\\]",
                "mat4(0.00390625, 0.0, 0.0, 0.0, "
                        + "0.0, 0.00390625, 0.0, 0.0, "
                        + "0.0, 0.0, 0.00390625, 0.0, "
                        + "0.03125, 0.03125, 0.03125, 1.0)");
    }

}
