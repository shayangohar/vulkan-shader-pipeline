package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The entity bridge's declaration rewrites: Iris's per-vertex identities (entityId,
 * blockEntityId, currentRenderedItemId) and the terrain attributes the entity layout lacks. Each
 * reads its declarations from the GLSL tree and edits the characters the declaration pattern it
 * replaces took, so a comment no longer decides whether a declaration is seen (KNOW-714).
 */
final class EntityDeclarations {
    private EntityDeclarations() {}

    private static final Set<String> IDENTITY_STORAGES = Set.of("uniform", "attribute", "in");
    private static final Set<String> IDENTITY_TYPES = Set.of("int", "float");
    private static final Set<String> UNBOUND_IDENTITIES = Set.of("entityId", "blockEntityId");
    private static final Set<List<String>> INPUT_QUALIFIERS = Set.of(List.of("attribute"), List.of("in"));
    private static final Set<String> VECTOR_TYPES = Set.of("float", "vec2", "vec3", "vec4");
    private static final Set<String> TERRAIN_ATTRIBUTES =
            Set.of("mc_Entity", "at_midBlock", "mc_midTexCoord", "at_tangent", "Tangent");
    /** The whitespace after a removed identity declaration, up to the next text. */
    private static final Pattern WHITESPACE_TAIL = Pattern.compile("\\s*");
    /** The spaces after a removed attribute; its line break stays. */
    private static final Pattern SPACE_TAIL = Pattern.compile("[ \\t]*");

    /**
     * One {@code storage int|float ...;} declaration that names an entity identity: its characters
     * from the storage keyword through the whitespace after it, and each declarator's name and text.
     */
    record IdentityDeclaration(int start, int end, String storage, String type, List<String> names,
                               List<String> declarators) {}

    /** The file-scope declarations, storage their last qualifier, whose declarators read an identity. */
    static List<IdentityDeclaration> identityDeclarations(String source) {
        GlslTree tree = GlslTree.parse(source);
        List<IdentityDeclaration> declarations = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Declaration declaration) || declaration.type() == null
                    || declaration.qualifiers().isEmpty() || !declaration.type().arrays().isEmpty()) {
                continue;
            }
            GlslTree.Qualifier storage = declaration.qualifiers().get(declaration.qualifiers().size() - 1);
            String type = declaration.type().name() < 0 ? "struct" : tree.text(declaration.type().name());
            List<String> declarators = declaration.declarators().stream()
                    .map(declarator -> tree.text(declarator.range())).toList();
            if (!IDENTITY_STORAGES.contains(tree.text(storage.keyword())) || !IDENTITY_TYPES.contains(type)
                    || declarators.stream().noneMatch(EntityDeclarations::readsIdentity)) {
                continue;
            }
            int end = GlslDeclarations.tailEnd(source, tree.offset(declaration.range().end()), WHITESPACE_TAIL);
            declarations.add(new IdentityDeclaration(tree.offset(storage.keyword()), end, tree.text(storage.keyword()),
                    type, declaration.declarators().stream().map(declarator -> tree.text(declarator.name())).toList(),
                    declarators));
        }
        return declarations;
    }

    private static boolean readsIdentity(String declarator) {
        return EntityVertexBridge.ENTITY_ID_NAMES.stream()
                .anyMatch(name -> GlslTokenRewriter.containsIdentifier(declarator, name));
    }

    static String removeEntityIdDeclarations(String source) {
        return removeEntityIdDeclarations(source, null);
    }

    /**
     * Drops the identity declarators, and those the program's uniform plan serves, from each
     * identity declaration; a declaration left with none goes with the whitespace after it.
     */
    static String removeEntityIdDeclarations(String source, UniformRegistry.ProgramInterface interfacePlan) {
        String input = source == null ? "" : source;
        List<GlslDeclarations.TextEdit> edits = new ArrayList<>();
        for (IdentityDeclaration declaration : identityDeclarations(input)) {
            List<String> remaining = new ArrayList<>();
            for (int index = 0; index < declaration.names().size(); index++) {
                String name = declaration.names().get(index);
                boolean executable = interfacePlan != null && interfacePlan.executableUniforms().stream()
                        .anyMatch(uniform -> uniform.name().equals(name));
                if (!EntityVertexBridge.ENTITY_ID_NAMES.contains(name) && !executable) {
                    remaining.add(declaration.declarators().get(index));
                }
            }
            edits.add(new GlslDeclarations.TextEdit(declaration.start(), declaration.end(), remaining.isEmpty() ? ""
                    : declaration.storage() + " " + declaration.type() + " " + String.join(", ", remaining) + ";"));
        }
        return GlslDeclarations.apply(input, edits);
    }

    /** The type the first identity declaration naming {@code name} gives it; null without one. */
    static String declaredEntityIdType(String source, String name) {
        for (IdentityDeclaration declaration : identityDeclarations(source)) {
            if (declaration.names().contains(name)) return declaration.type();
        }
        return null;
    }

    /**
     * Terrain-format programs carry no iris_Entity, and Iris applies EntityPatcher only
     * to vanilla-format programs, so an entityId/blockEntityId attribute there stays
     * unbound and GL reads 0 (MakeUp 9.5g declares attribute int blockEntityId in its
     * shared block vertex). Serve that value instead of rejecting the program.
     */
    static String defaultUnboundIdentityAttributes(String source) {
        String input = source == null ? "" : source;
        GlslTree tree = GlslTree.parse(input);
        List<GlslDeclarations.TextEdit> edits = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (!INPUT_QUALIFIERS.contains(variable.qualifiers()) || !variable.plain()
                    || !IDENTITY_TYPES.contains(variable.type()) || !UNBOUND_IDENTITIES.contains(variable.name())) {
                continue;
            }
            int start = GlslDeclarations.indentationStart(input, tree.offset(variable.declaration().range().start()));
            if (start < 0) continue;
            String zero = variable.type().equals("int") ? "0" : "0.0";
            edits.add(new GlslDeclarations.TextEdit(start, tree.offset(variable.declaration().range().end()),
                    "const " + variable.type() + " " + variable.name() + " = " + zero + ";"));
        }
        return GlslDeclarations.apply(input, edits);
    }

    /**
     * Removes the line-starting terrain attribute inputs the entity layout lacks, with the blank
     * lines before each and the spaces after it, each span starting where the previous one ended.
     * Iris's entity vertex format has no mc_Entity or at_midBlock (terrain inputs), so GL reads
     * the unbound attribute's default (0, 0, 0, 1): those two become that constant.
     */
    static String removeEntityAttributes(String source) {
        String input = source == null ? "" : source;
        GlslTree tree = GlslTree.parse(input);
        List<GlslDeclarations.TextEdit> edits = new ArrayList<>();
        int previousEnd = 0;
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (!INPUT_QUALIFIERS.contains(variable.qualifiers()) || !variable.plain()
                    || !VECTOR_TYPES.contains(variable.type()) || !TERRAIN_ATTRIBUTES.contains(variable.name())) {
                continue;
            }
            int start = GlslDeclarations.lineStartBefore(input,
                    tree.offset(variable.declaration().range().start()), previousEnd);
            if (start < 0) continue;
            previousEnd = GlslDeclarations.tailEnd(input, tree.offset(variable.declaration().range().end()),
                    SPACE_TAIL);
            edits.add(new GlslDeclarations.TextEdit(start, previousEnd,
                    unboundAttribute(variable.type(), variable.name())));
        }
        return GlslDeclarations.apply(input, edits);
    }

    private static String unboundAttribute(String type, String name) {
        if (!name.equals("mc_Entity") && !name.equals("at_midBlock")) return "";
        return "\nconst " + type + " " + name + " = " + switch (type) {
            case "float" -> "0.0";
            case "vec2" -> "vec2(0.0)";
            case "vec3" -> "vec3(0.0)";
            default -> "vec4(0.0, 0.0, 0.0, 1.0)";
        } + ";";
    }

    /**
     * Mid-texture coordinates arrive as a host vec2. A vec4-authored
     * declaration (BSL advanced materials) keeps matrix-multiplication
     * shape through an explicit constructor; vec2 uses the value directly.
     */
    static String midTexCoordValue(String strippedSource) {
        GlslTree tree = GlslTree.parse(strippedSource);
        boolean vec4 = GlslDeclarations.variables(tree).stream().anyMatch(variable ->
                INPUT_QUALIFIERS.contains(variable.qualifiers()) && variable.plain()
                        && variable.type().equals("vec4") && variable.name().equals("mc_midTexCoord")
                        && GlslDeclarations.lineStartBefore(strippedSource,
                        tree.offset(variable.declaration().range().start())) >= 0);
        return vec4 ? "vec4(MidTexCoord, 0.0, 1.0)" : "MidTexCoord";
    }

    /**
     * The source with each {@code const float|vecN mc_Entity = ...;} reading {@code value} of its
     * type instead, the declaration written {@code const type mc_Entity = value;}.
     */
    static String withEntityConstants(String source, UnaryOperator<String> value) {
        GlslTree tree = GlslTree.parse(source);
        List<GlslDeclarations.TextEdit> edits = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (variable.qualifiers().equals(List.of("const")) && VECTOR_TYPES.contains(variable.type())
                    && variable.name().equals("mc_Entity") && variable.declaration().declarators().size() == 1
                    && variable.declarator().initializer() != null) {
                edits.add(new GlslDeclarations.TextEdit(tree.offset(variable.declaration().range().start()),
                        tree.offset(variable.declaration().range().end()),
                        "const " + variable.type() + " mc_Entity = " + value.apply(variable.type()) + ";"));
            }
        }
        return GlslDeclarations.apply(source, edits);
    }
}
