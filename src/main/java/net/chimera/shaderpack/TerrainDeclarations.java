package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The terrain bridge's interface declarations, read from the GLSL tree. Each reader returns the
 * characters the declaration pattern it replaces took, so the rewrites keep their output while a
 * comment no longer decides whether a declaration is seen (KNOW-714).
 */
final class TerrainDeclarations {
    private TerrainDeclarations() {}

    static final Set<String> MODERN_STORAGES = Set.of("in", "out", "varying");
    static final Set<String> MODERN_INPUT_STORAGES = Set.of("in", "varying");
    private static final Set<String> INTERPOLATION = Set.of("flat", "noperspective", "smooth", "centroid", "sample");
    private static final Set<String> SHADOW_ATTRIBUTES = Set.of("mc_Entity", "mc_midTexCoord", "at_midBlock");
    /** What a shadow attribute's line ends with: its trailing spaces and its line break. */
    private static final Pattern SHADOW_ATTRIBUTE_TAIL = Pattern.compile("[ \\t]*(?:\\r?\\n|$)", Pattern.MULTILINE);

    /** One interface declaration: its characters, interpolation (null when none), storage, type and name. */
    record Declaration(int start, int end, String interpolation, String storage, String type, String name) {}

    /**
     * The modern interface declarations {@code [interpolation] storage type name;}, storage one of
     * {@code storages}, that start a line: each span begins where {@code (?m)^\s*} did and ends at
     * its semicolon. A declaration with array sizes, an initializer or another name is not one.
     */
    static List<Declaration> modern(String source, Set<String> storages) {
        GlslTree tree = GlslTree.parse(source);
        List<Declaration> declarations = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            List<String> qualifiers = variable.qualifiers();
            int storage = qualifiers.size() == 2 && INTERPOLATION.contains(qualifiers.get(0)) ? 1 : 0;
            if (qualifiers.size() != storage + 1 || !storages.contains(qualifiers.get(storage))
                    || !variable.plain() || variable.type().equals("struct")) {
                continue;
            }
            int start = GlslDeclarations.lineStartBefore(source, tree.offset(variable.declaration().range().start()));
            if (start < 0) continue;
            String interpolation = storage == 1 ? qualifiers.get(0) : null;
            declarations.add(new Declaration(start, tree.offset(variable.declaration().range().end()),
                    interpolation, qualifiers.get(storage), variable.type(), variable.name()));
        }
        return declarations;
    }

    /**
     * Legacy {@code keyword type name;} declarations, the keyword their last qualifier, each span
     * starting at the keyword (as the unanchored legacy patterns did) and ending at its semicolon.
     */
    static List<Declaration> keyword(String source, String keyword) {
        GlslTree tree = GlslTree.parse(source);
        List<Declaration> declarations = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            List<GlslTree.Qualifier> qualifiers = variable.declaration().qualifiers();
            if (qualifiers.isEmpty() || !variable.plain() || variable.type().equals("struct")) continue;
            GlslTree.Qualifier last = qualifiers.get(qualifiers.size() - 1);
            if (!tree.text(last.keyword()).equals(keyword)) continue;
            int end = tree.offset(variable.declaration().range().end());
            declarations.add(new Declaration(tree.offset(last.keyword()), end, null, keyword,
                    variable.type(), variable.name()));
        }
        return declarations;
    }

    /**
     * Shadow-stage {@code attribute type name;} lines for mc_Entity, mc_midTexCoord and
     * at_midBlock, with their indentation and line break; one with other text on its line is not one.
     */
    static List<Declaration> shadowAttributes(String source) {
        GlslTree tree = GlslTree.parse(source);
        List<Declaration> declarations = new ArrayList<>();
        for (GlslDeclarations.Variable variable : GlslDeclarations.variables(tree)) {
            if (!variable.qualifiers().equals(List.of("attribute")) || !variable.plain()
                    || !SHADOW_ATTRIBUTES.contains(variable.name()) || variable.type().equals("struct")) {
                continue;
            }
            int start = GlslDeclarations.indentationStart(source, tree.offset(variable.declaration().range().start()));
            int end = GlslDeclarations.tailEnd(source, tree.offset(variable.declaration().range().end()),
                    SHADOW_ATTRIBUTE_TAIL);
            if (start >= 0 && end >= 0) {
                declarations.add(new Declaration(start, end, null, "attribute", variable.type(), variable.name()));
            }
        }
        return declarations;
    }

    /** {@code source} without the declarations' characters. */
    static String without(String source, List<Declaration> declarations) {
        return GlslDeclarations.apply(source, declarations.stream()
                .map(value -> new GlslDeclarations.TextEdit(value.start(), value.end(), "")).toList());
    }
}
