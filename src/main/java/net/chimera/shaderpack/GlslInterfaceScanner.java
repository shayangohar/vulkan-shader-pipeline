package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Tree-based scan of a stage's cross-stage interface: every {@code in}, {@code out} and
 * {@code varying} declaration at file scope, of any type GLSL allows there.
 *
 * <p>A declaration may be a scalar, vector or matrix of 32-bit components, a {@code struct}
 * defined in the same source, or a sized array of either, and one statement may declare several
 * names. Each declaration knows how many locations it occupies (GLSL 4.60 section 4.4.1: one per
 * scalar or vector, one per matrix column, the sum of a struct's members, times an array's
 * length), and locations are packed in name order so both stages of a program agree on them
 * without sharing a declaration order. {@link #rewrite} re-emits every declaration through one
 * caller-supplied function, which is how converters give each name its explicit location.
 */
public final class GlslInterfaceScanner {
    /** Built-in types an interface variable may have, by the locations one occupies. */
    private static final Map<String, Integer> BUILTIN_SLOTS = builtinSlots();

    private GlslInterfaceScanner() {}

    /** {@code VARYING_UNWRITTEN_ZEROED:<name>}: a read fragment input no vertex output writes, declared as zero. */
    public static final String UNWRITTEN_ZEROED = "VARYING_UNWRITTEN_ZEROED:";

    /** The zero a non-array scalar, vector or matrix input reads as; null for any other type. */
    public static String zeroValue(Declaration declaration) {
        if (declaration.arraySize() != 0) return null;
        String type = declaration.type();
        if (type.equals("float")) return "0.0";
        if (type.equals("int")) return "0";
        if (type.equals("uint")) return "0u";
        if (type.equals("bool")) return "false";
        if (type.matches("(?:[iub]?vec[234]|mat[234](?:x[234])?)")) {
            return type + "(" + (type.startsWith("u") ? "0u" : type.startsWith("b") ? "false" : "0") + ")";
        }
        return null;
    }

    private static boolean zeroable(Declaration declaration) {
        return zeroValue(declaration) != null;
    }

    /**
     * One declared name. {@code arraySize} is 0 for a non-array; {@code slots} is the number of
     * locations the whole variable occupies.
     */
    public record Declaration(
            String name,
            String type,
            String qualifier,
            boolean input,
            boolean output,
            boolean referenced,
            int arraySize,
            int slots
    ) {
        /** The type as a declaration spells it after the name, such as {@code vec3[9]}. */
        public String typeWithArray() {
            return arraySize == 0 ? type : type + "[" + arraySize + "]";
        }

        /** Whether the variable holds integers, which a fragment input must take {@code flat}. */
        public boolean integral() {
            return type.equals("int") || type.equals("uint")
                    || type.startsWith("ivec") || type.startsWith("uvec");
        }

        /** {@code name} or {@code name[N]}, as the declarator is written. */
        public String declarator() {
            return arraySize == 0 ? name : name + "[" + arraySize + "]";
        }
    }

    public record StageInterface(
            List<Declaration> inputs,
            List<Declaration> outputs,
            Map<String, Integer> locations,
            List<String> deviations
    ) {
        public StageInterface {
            inputs = sortedDeclarations(inputs);
            outputs = sortedDeclarations(outputs);
            locations = Collections.unmodifiableMap(new TreeMap<>(locations));
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }

        public Declaration input(String name) {
            return inputs.stream().filter(value -> value.name().equals(name)).findFirst().orElse(null);
        }

        public Declaration output(String name) {
            return outputs.stream().filter(value -> value.name().equals(name)).findFirst().orElse(null);
        }

        private static List<Declaration> sortedDeclarations(List<Declaration> values) {
            return values == null ? List.of() : values.stream()
                    .filter(value -> value != null)
                    .sorted(Comparator.comparing(Declaration::name).thenComparing(Declaration::type))
                    .toList();
        }
    }

    public record ProgramMatch(
            Map<String, Integer> locations,
            List<String> deviations
    ) {
        public ProgramMatch {
            locations = Collections.unmodifiableMap(new TreeMap<>(locations));
            deviations = deviations == null ? List.of() : deviations.stream().distinct().sorted().toList();
        }

        public boolean executable() {
            return deviations.stream().allMatch(deviation -> deviation.startsWith(UNWRITTEN_ZEROED));
        }
    }

    /** One interface statement: its token range and the names it declares. */
    private record Statement(int start, int end, List<Declaration> declarations) {}

    private record Parse(List<GlslLexer.Token> tokens, List<Statement> statements, List<String> deviations) {}

    public static StageInterface scan(String source, boolean vertexStage) {
        Parse parse = parse(source, vertexStage);
        List<Declaration> inputs = new ArrayList<>();
        List<Declaration> outputs = new ArrayList<>();
        List<String> deviations = new ArrayList<>(parse.deviations());
        Map<String, Declaration> declarations = new LinkedHashMap<>();
        for (Statement statement : parse.statements()) {
            for (Declaration declaration : statement.declarations()) {
                Declaration previous = declarations.putIfAbsent(declaration.name(), declaration);
                if (previous != null && (!previous.typeWithArray().equals(declaration.typeWithArray())
                        || previous.input() != declaration.input()
                        || previous.output() != declaration.output())) {
                    deviations.add("PROGRAM_INTERFACE_CONFLICT:" + declaration.name());
                    continue;
                }
                if (previous != null) {
                    continue;
                }
                if (declaration.input()) {
                    inputs.add(declaration);
                }
                if (declaration.output()) {
                    outputs.add(declaration);
                }
            }
        }
        return new StageInterface(inputs, outputs, packedLocations(declarations.values()), deviations);
    }

    public static ProgramMatch match(StageInterface vertex, StageInterface fragment) {
        return match(vertex, fragment, false);
    }

    /**
     * Pairs each fragment input with the vertex output of its name. With {@code zeroUnwritten}, a
     * read input the vertex never writes is not a failure: Iris adds a zeroed vertex output for it,
     * so the converter declares it as a zero (VARYING_UNWRITTEN_ZEROED); Bliss's composite3 reads
     * an exposure its vertex writes only under another option.
     */
    public static ProgramMatch match(StageInterface vertex, StageInterface fragment, boolean zeroUnwritten) {
        List<String> deviations = new ArrayList<>();
        if (vertex == null || fragment == null) {
            return new ProgramMatch(Map.of(), List.of("PROGRAM_INTERFACE_STAGE_MISSING"));
        }
        List<Declaration> matched = new ArrayList<>();
        for (Declaration input : fragment.inputs()) {
            Declaration output = vertex.output(input.name());
            if (output == null) {
                if (input.referenced()) {
                    deviations.add((zeroUnwritten && zeroable(input) ? UNWRITTEN_ZEROED : "PROGRAM_VARYING_UNMATCHED:")
                            + input.name());
                }
                continue;
            }
            if (!output.typeWithArray().equals(input.typeWithArray())) {
                deviations.add("PROGRAM_INTERFACE_CONFLICT:" + input.name());
                continue;
            }
            matched.add(input);
        }
        return new ProgramMatch(packedLocations(matched), deviations);
    }

    /**
     * Re-emits every interface statement of {@code source}: each declared name is replaced by
     * what {@code emit} returns for it (an empty string drops it), and the rest of the source is
     * untouched. A statement that declares several names becomes one declaration per name. When
     * {@code emit} returns null for every name of a statement, that statement stays as written.
     *
     * @throws IllegalArgumentException when the interface itself cannot be parsed
     */
    public static String rewrite(String source, boolean vertexStage, Function<Declaration, String> emit) {
        Parse parse = parse(source, vertexStage);
        if (!parse.deviations().isEmpty()) {
            throw new IllegalArgumentException("interface declarations unsupported: " + parse.deviations());
        }
        List<GlslLexer.Token> tokens = parse.tokens();
        StringBuilder result = new StringBuilder();
        int next = 0;
        for (Statement statement : parse.statements()) {
            result.append(GlslLexer.render(tokens.subList(next, statement.start())));
            StringBuilder replacement = new StringBuilder();
            boolean kept = true;
            for (Declaration declaration : statement.declarations()) {
                String emitted = emit.apply(declaration);
                if (emitted != null) {
                    kept = false;
                    replacement.append(emitted);
                }
            }
            result.append(kept ? GlslLexer.render(tokens.subList(statement.start(), statement.end()))
                    : replacement);
            next = statement.end();
        }
        result.append(GlslLexer.render(tokens.subList(next, tokens.size())));
        return result.toString();
    }

    /** Locations in name order, each declaration taking as many as it occupies. */
    private static Map<String, Integer> packedLocations(Iterable<Declaration> declarations) {
        Map<String, Declaration> byName = new TreeMap<>();
        for (Declaration declaration : declarations) {
            byName.putIfAbsent(declaration.name(), declaration);
        }
        Map<String, Integer> locations = new TreeMap<>();
        int location = 0;
        for (Declaration declaration : byName.values()) {
            locations.put(declaration.name(), location);
            location += declaration.slots();
        }
        return locations;
    }


    /**
     * The interface statements of the tree's file-scope declarations, in source order. A source
     * the tree cannot parse has an interface this cannot read.
     */
    private static Parse parse(String source, boolean vertexStage) {
        GlslTree tree;
        try {
            tree = GlslTree.parse(source);
        } catch (GlslParser.ParseFailure failure) {
            return new Parse(GlslLexer.lex(source), List.of(), List.of(DECLARATION_UNSUPPORTED));
        }
        Map<String, Integer> structSlots = structSlots(tree);
        List<Statement> statements = new ArrayList<>();
        List<String> deviations = new ArrayList<>();
        for (GlslTree.External external : tree.externals()) {
            List<GlslTree.Qualifier> qualifiers = external instanceof GlslTree.Declaration declaration
                    ? declaration.qualifiers()
                    : external instanceof GlslTree.InterfaceBlock block ? block.qualifiers() : List.of();
            int[] storage = storageQualifier(tree, qualifiers);
            if (storage == null) continue;
            Statement statement = statement(tree, external, qualifiers, storage, vertexStage, structSlots, deviations);
            if (statement != null) statements.add(statement);
        }
        return new Parse(tree.tokens(), statements, deviations);
    }

    private static final String DECLARATION_UNSUPPORTED = "PROGRAM_INTERFACE_DECLARATION_UNSUPPORTED";

    /**
     * Where a declaration's interface statement starts and where its {@code in}, {@code out} or
     * {@code varying} sits, as qualifier indices: the first {@code [layout(...)] [interpolation]
     * storage} run of its qualifiers. A qualifier before that run (invariant) stays outside the
     * statement. Null when the declaration has no storage qualifier.
     */
    private static int[] storageQualifier(GlslTree tree, List<GlslTree.Qualifier> qualifiers) {
        for (int start = 0; start < qualifiers.size(); start++) {
            int at = start;
            if (keyword(tree, qualifiers, at).equals("layout")) at++;
            if (at < qualifiers.size() && isInterpolation(keyword(tree, qualifiers, at))) at++;
            if (at < qualifiers.size() && isStorage(keyword(tree, qualifiers, at))) return new int[] {start, at};
        }
        return null;
    }

    private static String keyword(GlslTree tree, List<GlslTree.Qualifier> qualifiers, int index) {
        return tree.text(qualifiers.get(index).keyword());
    }

    /**
     * The interface statement a declaration makes, or null when it declares none this can read,
     * with its deviation added. Only precision may follow the storage qualifier: another word
     * there reads as the type, which no interface variable has.
     */
    private static Statement statement(GlslTree tree, GlslTree.External external, List<GlslTree.Qualifier> qualifiers,
                                       int[] storage, boolean vertexStage, Map<String, Integer> structSlots,
                                       List<String> deviations) {
        for (int index = storage[1] + 1; index < qualifiers.size(); index++) {
            if (!isPrecision(keyword(tree, qualifiers, index))) {
                deviations.add("PROGRAM_VARYING_TYPE_UNSUPPORTED:" + keyword(tree, qualifiers, index));
                return null;
            }
        }
        if (external instanceof GlslTree.InterfaceBlock block) {
            deviations.add("PROGRAM_VARYING_TYPE_UNSUPPORTED:" + tree.text(block.name()));
            return null;
        }
        GlslTree.Declaration declaration = (GlslTree.Declaration) external;
        if (declaration.type() == null) {
            deviations.add(DECLARATION_UNSUPPORTED);
            return null;
        }
        String type = typeName(tree, declaration.type());
        Integer typeSlots = BUILTIN_SLOTS.containsKey(type) ? BUILTIN_SLOTS.get(type) : structSlots.get(type);
        if (typeSlots == null) {
            deviations.add("PROGRAM_VARYING_TYPE_UNSUPPORTED:" + type);
            return null;
        }
        String direction = keyword(tree, qualifiers, storage[1]);
        boolean varying = direction.equals("varying");
        String interpolation = storage[1] > storage[0] && isInterpolation(keyword(tree, qualifiers, storage[1] - 1))
                ? keyword(tree, qualifiers, storage[1] - 1) : null;
        InterfaceType kind = new InterfaceType(type, interpolation, direction.equals("in") || varying && !vertexStage,
                direction.equals("out") || varying && vertexStage, typeSlots);
        int start = qualifiers.get(storage[0]).range().start();
        int end = declaration.range().end();
        List<Declaration> declared = declarators(tree, declaration, start, end, kind);
        if (declared == null || declared.isEmpty()) {
            deviations.add(DECLARATION_UNSUPPORTED);
            return null;
        }
        return new Statement(start, end, declared);
    }

    /** The type's name; {@code struct} for an inline struct. */
    private static String typeName(GlslTree tree, GlslTree.TypeSpec type) {
        return type.name() >= 0 ? tree.text(type.name()) : "struct";
    }

    /** What one interface statement's declarators share: type, interpolation, direction and size. */
    private record InterfaceType(String type, String interpolation, boolean input, boolean output, int slots) {}

    /**
     * The variables one interface statement declares, each with any array size, or null when the
     * type has array sizes or a declarator has an initializer or a size this cannot read.
     */
    private static List<Declaration> declarators(GlslTree tree, GlslTree.Declaration declaration, int start, int end,
                                                 InterfaceType kind) {
        if (!declaration.type().arrays().isEmpty()) return null;
        List<Declaration> declared = new ArrayList<>();
        for (GlslTree.Declarator declarator : declaration.declarators()) {
            int length = arrayLength(tree, declarator);
            if (length < 0 || declarator.initializer() != null) return null;
            String name = tree.text(declarator.name());
            boolean referenced = referencedOutsideDeclaration(tree.tokens(), name, start, end);
            declared.add(new Declaration(name, kind.type(), kind.interpolation(), kind.input(), kind.output(),
                    referenced, length, kind.slots() * Math.max(length, 1)));
        }
        return List.copyOf(declared);
    }

    /**
     * A declarator's array length: 0 without one, the literal {@code N} of one {@code [N]}, or -1
     * for anything else (a named or unsized length, more than one dimension).
     */
    private static int arrayLength(GlslTree tree, GlslTree.Declarator declarator) {
        if (declarator.arrays().isEmpty()) return 0;
        if (declarator.arrays().size() > 1) return -1;
        GlslTree.Range array = declarator.arrays().get(0);
        int size = GlslLexer.nextSignificant(tree.tokens(), array.start());
        int close = size < 0 ? -1 : GlslLexer.nextSignificant(tree.tokens(), size);
        if (close != array.end() - 1) return -1;
        int length = arraySize(tree.tokens().get(size));
        return length > 0 ? length : -1;
    }

    /**
     * Locations each file-scope {@code struct} occupies: the sum of its members, each a built-in
     * or an earlier struct, times any array length. A struct with a member this cannot size is
     * left out, so an interface variable of that type is reported unsupported.
     */
    private static Map<String, Integer> structSlots(GlslTree tree) {
        Map<String, Integer> result = new HashMap<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Declaration declaration) || declaration.type() == null) continue;
            GlslTree.Struct struct = declaration.type().struct();
            if (struct == null || struct.name() < 0) continue;
            Integer slots = memberSlots(tree, struct.members(), result);
            if (slots != null && slots > 0) result.put(tree.text(struct.name()), slots);
        }
        return result;
    }

    /** Sum of member locations, or null for a member this cannot size. */
    private static Integer memberSlots(GlslTree tree, List<GlslTree.Declaration> members,
                                       Map<String, Integer> structs) {
        int total = 0;
        for (GlslTree.Declaration member : members) {
            if (member.type() == null || !member.type().arrays().isEmpty() || member.declarators().isEmpty()
                    || member.qualifiers().stream().anyMatch(value -> !isPrecision(tree.text(value.keyword())))) {
                return null;
            }
            String type = typeName(tree, member.type());
            Integer typeSlots = BUILTIN_SLOTS.containsKey(type) ? BUILTIN_SLOTS.get(type) : structs.get(type);
            if (typeSlots == null) return null;
            for (GlslTree.Declarator declarator : member.declarators()) {
                int length = arrayLength(tree, declarator);
                if (length < 0 || declarator.initializer() != null) return null;
                total += typeSlots * Math.max(length, 1);
            }
        }
        return total;
    }

    /** A literal array length; a named length is not resolved here. */
    private static int arraySize(GlslLexer.Token token) {
        if (token.kind() != GlslLexer.Kind.NUMBER) {
            return -1;
        }
        try {
            return Integer.parseInt(token.text().replaceAll("[uU]$", ""));
        } catch (NumberFormatException failure) {
            return -1;
        }
    }

    private static Map<String, Integer> builtinSlots() {
        Map<String, Integer> slots = new HashMap<>();
        for (String scalar : List.of("float", "int", "uint")) {
            slots.put(scalar, 1);
        }
        for (int size = 2; size <= 4; size++) {
            slots.put("vec" + size, 1);
            slots.put("ivec" + size, 1);
            slots.put("uvec" + size, 1);
            slots.put("mat" + size, size);
            for (int rows = 2; rows <= 4; rows++) {
                slots.put("mat" + size + "x" + rows, size);
            }
        }
        return Map.copyOf(slots);
    }

    /** Whether {@code name} is spelled anywhere outside the statement's tokens {@code [start, end)}. */
    private static boolean referencedOutsideDeclaration(
            List<GlslLexer.Token> tokens,
            String name,
            int declarationStart,
            int declarationEnd
    ) {
        for (int index = 0; index < tokens.size(); index++) {
            if (index >= declarationStart && index < declarationEnd) {
                continue;
            }
            if (tokens.get(index).identifier(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isStorage(String value) {
        return value.equals("in") || value.equals("out") || value.equals("varying");
    }

    private static boolean isInterpolation(String value) {
        return value.equals("flat") || value.equals("noperspective")
                || value.equals("smooth") || value.equals("centroid") || value.equals("sample");
    }

    private static boolean isPrecision(String value) {
        return value.equals("lowp") || value.equals("mediump") || value.equals("highp");
    }
}
