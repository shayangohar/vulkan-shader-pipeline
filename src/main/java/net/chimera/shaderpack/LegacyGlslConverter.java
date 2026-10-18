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
    private static final Pattern VARYING_DECL =
            Pattern.compile("(?m)^\\s*varying\\s+(float|vec2|vec3|vec4)\\s+(\\w+)\\s*;");
    private static final Pattern MODERN_VARYING_LIST_DECL = Pattern.compile(
            "(?m)^([ \\t]*)(?:(flat|noperspective|smooth|centroid|sample)\\s+)?"
                    + "(in|out|varying)\\s+(float|int|vec2|vec3|vec4)\\s+"
                    + "([A-Za-z_]\\w*(?:\\s*,\\s*[A-Za-z_]\\w*)+)\\s*;");
    /** Pack metadata declarations consumed by PackConfig, not executable GLSL. */
    private static final Pattern CONSUMED_CONSTS =
            Pattern.compile("(?m)^\\s*(?:const\\s+)?(?:int|float|bool|vec4)\\s+"
                    + "(colortex\\d+Format|gaux\\d+Format|colortex\\d+(?:Clear|ClearColor|MipmapEnabled)"
                    + "|shadowMapResolution|shadowDistance|shadowMapDistance|shadowMapSize|shadowMapFov"
                    + "|shadowDistanceRenderMul|sunPathRotation|sunPathOffset)"
                    + "\\s*=\\s*[A-Za-z0-9+_.(), /-]+\\s*;\\s*(?://.*)?$");

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
        String checkSource = CONSUMED_CONSTS.matcher(source).replaceAll("");
        Matcher matcher = CONSUMED_CONSTS.matcher(source);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            boolean injected = packConstants != null && packConstants.containsKey(name);
            boolean referenced =
                    GlslTokenRewriter.containsIdentifier(checkSource, name);
            matcher.appendReplacement(output,
                    !referenced || injected ? "" : Matcher.quoteReplacement(matcher.group(0)));
        }
        matcher.appendTail(output);
        return output.toString();
    }
    static final Pattern KNOWN_LEGACY_EXTENSIONS = Pattern.compile(
            "(?im)^\\s*#extension\\s+GL_ARB_shader_texture_lod\\s*:\\s*(?:enable|require|disable)\\s*$\\r?\\n?");
    static final Pattern MODERN_LAYOUT_DECL = Pattern.compile(
            "(?m)^([ \\t]*)layout\\s*\\([^;{}\\r\\n]*\\)\\s*((?:(?:flat|noperspective|smooth|centroid|sample)\\s+)?)"
                    + "(in|out|varying)\\b");
    static final Pattern PRECISION_DECL = Pattern.compile(
            "(?m)^\\s*precision\\s+(?:lowp|mediump|highp)\\s+(?:float|int)\\s*;\\s*$\\r?\\n?");

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
            if (PostVertexBridge.unproducedAndUnread(src, modern, name, terrainLayout)) {
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
        Matcher matcher = VARYING_DECL.matcher(src);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String type = matcher.group(1);
            String name = matcher.group(2);
            if (PostVertexBridge.unproducedAndUnread(src, matcher, name, terrainLayout)) {
                out.append(src, last, matcher.start());
                last = matcher.end();
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
            out.append(src, last, matcher.start());
            out.append("layout(location = ").append(location).append(") "
                    + "in ").append(type).append(' ').append(name).append(';');
            last = matcher.end();
        }
        out.append(src, last, src.length());
        return out.toString();
    }

    static boolean containsIdentifier(String source, String name) {
        return GlslTokenRewriter.containsIdentifier(source, name);
    }

    static String removePackMetadataConstants(String source) {
        Matcher declarations = Pattern.compile(
                "(?m)^\\s*const\\s+(?:int|float|bool|vec4)\\s+"
                        + "((?:colortex|gaux)\\d+(?:Format|Clear|ClearColor|MipmapEnabled))"
                        + "\\s*=\\s*[^;]+;\\s*(?://.*)?$").matcher(source);
        List<PostVertexBridge.PostVarying> matches = new ArrayList<>();
        while (declarations.find()) {
            matches.add(new PostVertexBridge.PostVarying(declarations.start(), declarations.end(),
                    declarations.group(1), declarations.group(1)));
        }
        for (PostVertexBridge.PostVarying declaration : matches) {
            String withoutDeclaration = source.substring(0, declaration.start())
                    + source.substring(declaration.end());
            if (containsIdentifier(withoutDeclaration, declaration.name())) {
                throw new IllegalArgumentException("pack metadata constant is used: " + declaration.name());
            }
        }
        return declarations.reset().replaceAll("");
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
        Pattern declarations = Pattern.compile(
                "(?m)^\\s*uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?"
                        + "(?:bool|int|float|vec[234]|ivec[234]|mat[234])\\s+([^;]+);[ \\t]*(?://[^\\r\\n]*)?\\s*$");
        Matcher matcher = declarations.matcher(source);
        StringBuffer stripped = new StringBuffer();
        while (matcher.find()) {
            boolean removable = true;
            for (String name : declaratorNames(matcher.group(1))) {
                if (name == null || !served.contains(name)) {
                    removable = false;
                    break;
                }
            }
            matcher.appendReplacement(stripped, removable ? "" : Matcher.quoteReplacement(matcher.group(0)));
        }
        matcher.appendTail(stripped);
        String withoutServed = stripped.toString();
        String checkSource = declarations.matcher(withoutServed).replaceAll("");
        Matcher remaining = declarations.matcher(withoutServed);
        StringBuffer output = new StringBuffer();
        while (remaining.find()) {
            boolean referenced = false;
            for (String name : declaratorNames(remaining.group(1))) {
                if (name != null && GlslTokenRewriter.containsIdentifier(checkSource, name)) {
                    referenced = true;
                    break;
                }
            }
            remaining.appendReplacement(output,
                    referenced ? Matcher.quoteReplacement(remaining.group(0)) : "");
        }
        remaining.appendTail(output);
        return output.toString();
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

    /** Plain declarator names from a uniform declaration list, or null entries for exotic forms. */
    private static List<String> declaratorNames(String declarators) {
        List<String> names = new ArrayList<>();
        for (String declarator : declarators.split(",")) {
            String name = declarator.trim();
            int equals = name.indexOf('=');
            if (equals >= 0) name = name.substring(0, equals).trim();
            names.add(name.matches("[A-Za-z_]\\w*") ? name : null);
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
        Matcher matcher = Pattern.compile(
                // A trailing line comment (Bliss: `uniform sampler2D noisetex;//depth`) still ends it.
                "(?m)^\\s*uniform\\s+(?:[iu]?sampler\\w*|u?i?image\\w*)\\s+([^;]+);[ \\t]*(?://[^\\n]*)?$")
                .matcher(source);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String checkSource = source.substring(0, matcher.start())
                    + source.substring(matcher.end());
            boolean referenced = false;
            for (String name : declaratorNames(matcher.group(1))) {
                if (name != null && GlslTokenRewriter.containsIdentifier(checkSource, name)) {
                    referenced = true;
                    break;
                }
            }
            matcher.appendReplacement(output, referenced
                    ? Matcher.quoteReplacement(matcher.group(0)) : "");
        }
        matcher.appendTail(output);
        return output.toString();
    }

    static String injectMainPrologue(String source, String statement) {
        Matcher main = Pattern.compile(
                "\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)\\s*\\{")
                .matcher(source);
        if (!main.find()) {
            throw new IllegalArgumentException("entity vertex main body is missing");
        }
        return source.substring(0, main.end()) + "\n    " + statement + source.substring(main.end());
    }

    static String convertTextureCalls(String src) {
        return GlslTokenRewriter.rewriteTextureCalls(GlslTokenRewriter.renameReservedSamplerParameters(src));
    }

    /** Expands comma-separated stage declarations before assigning locations. */
    static String expandModernVaryingLists(String source) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        Matcher matcher = MODERN_VARYING_LIST_DECL.matcher(source);
        StringBuilder result = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            result.append(source, last, matcher.start());
            String indent = matcher.group(1);
            String qualifier = matcher.group(2) == null ? "" : matcher.group(2) + " ";
            String type = matcher.group(4);
            for (String name : matcher.group(5).split(",")) {
                result.append(indent).append(qualifier).append(matcher.group(3)).append(' ')
                        .append(type).append(' ').append(name.trim()).append(';').append('\n');
            }
            last = matcher.end();
        }
        result.append(source, last, source.length());
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
