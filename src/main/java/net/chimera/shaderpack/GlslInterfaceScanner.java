package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
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

    /**
     * The stage whose interface a source declares. A geometry stage's inputs are arrays with one
     * element per vertex of its primitive ({@code in vec2 texcoord[]}); the scan reads each as the
     * value one vertex passes, so it pairs with the vertex stage's output of that type. The
     * tessellation stages' inputs are arrays the same way, and so are the control stage's outputs,
     * one element per vertex of its output patch.
     */
    public enum Stage {
        VERTEX, TESS_CONTROL, TESS_EVALUATION, GEOMETRY, FRAGMENT;

        /** Whether a stage of this kind reads one value per vertex of a primitive or patch. */
        boolean perVertexInputs() {
            return this == TESS_CONTROL || this == TESS_EVALUATION || this == GEOMETRY;
        }

        /** The program stage name (PackProbe's: vertex, tess_control, ...) of a stage of this kind. */
        String programStage() {
            return switch (this) {
                case VERTEX -> "vertex";
                case TESS_CONTROL -> "tess_control";
                case TESS_EVALUATION -> "tess_evaluation";
                case GEOMETRY -> "geometry";
                case FRAGMENT -> "fragment";
            };
        }

        /** The stage a program stage of this name (PackProbe's: vertex, tess_control, ...) declares. */
        static Stage of(String programStage) {
            return switch (programStage) {
                case "vertex" -> VERTEX;
                case "tess_control" -> TESS_CONTROL;
                case "tess_evaluation" -> TESS_EVALUATION;
                case "geometry" -> GEOMETRY;
                default -> FRAGMENT;
            };
        }
    }

    /**
     * One declared name. {@code arraySize} is 0 for a non-array; {@code slots} is the number of
     * locations the whole variable occupies. A geometry stage's input is {@code perVertex}: its
     * outer array dimension, one element per vertex, is not part of its type or size.
     */
    public record Declaration(
            String name,
            String type,
            String qualifier,
            boolean input,
            boolean output,
            boolean referenced,
            int arraySize,
            int slots,
            boolean perVertex
    ) {
        /** The type as a declaration spells it after the name, such as {@code vec3[9]}. */
        public String typeWithArray() {
            return arraySize == 0 ? type : type + "[" + arraySize + "]";
        }

        /** Whether the variable holds integers, which a fragment input must take {@code flat}. */
        public boolean integral() {
            return type.equals("int") || type.equals("uint") || type.startsWith("ivec") || type.startsWith("uvec")
                    || type.matches("u?int16_t|[iu]16vec[234]");
        }

        /** {@code name} or {@code name[N]}, as the declarator is written; a per-vertex input keeps its {@code []}. */
        public String declarator() {
            return (perVertex ? name + "[]" : name) + (arraySize == 0 ? "" : "[" + arraySize + "]");
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

        /** Each input the stage reads, at location 0: a fullscreen pass's inputs, which no vertex stage feeds. */
        public Map<String, Integer> readInputLocations() {
            Map<String, Integer> result = new TreeMap<>();
            inputs.stream().filter(Declaration::referenced).forEach(input -> result.put(input.name(), 0));
            return result;
        }

        /** Each input the stage reads, with its type. */
        public Map<String, String> readInputTypes() {
            Map<String, String> result = new TreeMap<>();
            inputs.stream().filter(Declaration::referenced)
                    .forEach(input -> result.put(input.name(), input.typeWithArray()));
            return result;
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
            return deviations.isEmpty();
        }
    }

    /**
     * One interface statement: its token range, where its qualifiers and type end, the names it
     * declares, and each one's declarator range (its name and arrays; a declarator with an
     * initializer makes no statement), in the same order.
     */
    private record Statement(int start, int typeEnd, int end, List<Declaration> declarations,
                             List<GlslTree.Range> declarators) {}

    private record Parse(List<GlslLexer.Token> tokens, List<Statement> statements, List<String> deviations) {}

    public static StageInterface scan(String source, boolean vertexStage) {
        return scan(source, vertexStage ? Stage.VERTEX : Stage.FRAGMENT);
    }

    public static StageInterface scan(String source, Stage stage) {
        Parse parse = parse(source, stage);
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

    /**
     * Pairs each fragment input with the vertex output of its name. CrossStageRepair has already
     * given the vertex every output Iris's repair adds, so a read input still unmatched, or matched
     * to an output of another type, is one Iris's link refuses too.
     */
    public static ProgramMatch match(StageInterface vertex, StageInterface fragment) {
        List<String> deviations = new ArrayList<>();
        if (vertex == null || fragment == null) {
            return new ProgramMatch(Map.of(), List.of("PROGRAM_INTERFACE_STAGE_MISSING"));
        }
        List<Declaration> matched = new ArrayList<>();
        for (Declaration input : fragment.inputs()) {
            Declaration output = vertex.output(input.name());
            if (output == null) {
                if (input.referenced()) {
                    deviations.add("PROGRAM_VARYING_UNMATCHED:" + input.name());
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
     * Pairs a vertex stage with a geometry stage's per-vertex inputs, and the geometry stage's
     * outputs with a fragment stage, as Iris links three stages. The locations are the fragment
     * stage's inputs'.
     */
    public static ProgramMatch match(StageInterface vertex, StageInterface geometry, StageInterface fragment) {
        return match(Arrays.asList(vertex, geometry, fragment));
    }

    /**
     * Pairs each stage of {@code chain}, vertex first and fragment last, with the stage after it,
     * as Iris links every stage a program runs. The locations are the fragment stage's inputs'.
     */
    public static ProgramMatch match(List<StageInterface> chain) {
        List<String> deviations = new ArrayList<>();
        ProgramMatch last = null;
        for (int index = 1; index < chain.size(); index++) {
            last = match(chain.get(index - 1), chain.get(index));
            deviations.addAll(last.deviations());
        }
        return new ProgramMatch(last == null ? Map.of() : last.locations(), deviations);
    }

    /**
     * Re-emits every interface statement of {@code source}: each declared name is replaced by
     * what {@code emit} returns for it (an empty string drops it), and the rest of the source is
     * untouched. A statement that declares several names becomes one declaration per name. When
     * {@code emit} returns null for every name of a statement, that statement stays as written; a
     * name it returns null for beside others keeps a declaration of its own.
     *
     * @throws IllegalArgumentException when the interface itself cannot be parsed
     */
    public static String rewrite(String source, boolean vertexStage, Function<Declaration, String> emit) {
        return rewrite(source, vertexStage ? Stage.VERTEX : Stage.FRAGMENT, emit);
    }

    /** {@link #rewrite(String, boolean, Function)} for a source of any {@link Stage}. */
    public static String rewrite(String source, Stage stage, Function<Declaration, String> emit) {
        Parse parse = parse(source, stage);
        if (!parse.deviations().isEmpty()) {
            throw new IllegalArgumentException("interface declarations unsupported: " + parse.deviations());
        }
        List<GlslLexer.Token> tokens = parse.tokens();
        StringBuilder result = new StringBuilder();
        int next = 0;
        for (Statement statement : parse.statements()) {
            result.append(GlslLexer.render(tokens.subList(next, statement.start())));
            List<String> emitted = new ArrayList<>();
            statement.declarations().forEach(declaration -> emitted.add(emit.apply(declaration)));
            if (emitted.stream().allMatch(Objects::isNull)) {
                result.append(GlslLexer.render(tokens.subList(statement.start(), statement.end())));
            } else {
                // A name emit leaves alone keeps its own declaration: the statement's qualifiers
                // and type, then its declarator, so `out vec2 a, b;` keeps b when only a changes.
                String prefix = GlslLexer.render(tokens.subList(statement.start(), statement.typeEnd()));
                for (int index = 0; index < emitted.size(); index++) {
                    GlslTree.Range declarator = statement.declarators().get(index);
                    result.append(emitted.get(index) != null ? emitted.get(index) : prefix + " "
                            + GlslLexer.render(tokens.subList(declarator.start(), declarator.end())) + ";\n");
                }
            }
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
    private static Parse parse(String source, Stage stage) {
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
            Statement statement = statement(tree, external, qualifiers, storage, stage, structSlots, deviations);
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
     * The declaration of a variable this can read the interface of, or null, with any deviation
     * added. Only precision may follow the storage qualifier: another word there reads as the
     * type, which no interface variable has. A default-qualifier statement, such as Alpha
     * Piscium's {@code layout(early_fragment_tests) in;}, declares no variable and stays as written.
     */
    private static GlslTree.Declaration variableDeclaration(GlslTree tree, GlslTree.External external,
            List<GlslTree.Qualifier> qualifiers, int[] storage, List<String> deviations) {
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
        if (declaration.type() != null) return declaration;
        if (!GlslDeclarations.qualifiersOnly(declaration)) deviations.add(DECLARATION_UNSUPPORTED);
        return null;
    }

    /** The interface statement a declaration makes, or null when it declares none this can read. */
    private static Statement statement(GlslTree tree, GlslTree.External external, List<GlslTree.Qualifier> qualifiers,
                                       int[] storage, Stage stage, Map<String, Integer> structSlots,
                                       List<String> deviations) {
        GlslTree.Declaration declaration = variableDeclaration(tree, external, qualifiers, storage, deviations);
        if (declaration == null) return null;
        String type = typeName(tree, declaration.type());
        Integer typeSlots = BUILTIN_SLOTS.containsKey(type) ? BUILTIN_SLOTS.get(type) : structSlots.get(type);
        if (typeSlots == null) {
            deviations.add("PROGRAM_VARYING_TYPE_UNSUPPORTED:" + type);
            return null;
        }
        InterfaceType kind = interfaceType(tree, qualifiers, storage, stage, type, typeSlots);
        if (kind == null) {
            deviations.add(DECLARATION_UNSUPPORTED);
            return null;
        }
        int start = qualifiers.get(storage[0]).range().start();
        int end = declaration.range().end();
        List<Declaration> declared = declarators(tree, declaration, start, end, kind);
        if (declared == null || declared.isEmpty()) {
            deviations.add(DECLARATION_UNSUPPORTED);
            return null;
        }
        return new Statement(start, declaration.type().range().end(), end, declared,
                declaration.declarators().stream().map(GlslTree.Declarator::range).toList());
    }

    /**
     * What a statement's declarators share, or null for a {@code varying} in a geometry or
     * tessellation stage, which GL does not have.
     */
    private static InterfaceType interfaceType(GlslTree tree, List<GlslTree.Qualifier> qualifiers, int[] storage,
                                               Stage stage, String type, int typeSlots) {
        String direction = keyword(tree, qualifiers, storage[1]);
        boolean varying = direction.equals("varying");
        if (varying && stage.perVertexInputs()) return null;
        String interpolation = storage[1] > storage[0] && isInterpolation(keyword(tree, qualifiers, storage[1] - 1))
                ? keyword(tree, qualifiers, storage[1] - 1) : null;
        boolean input = direction.equals("in") || varying && stage == Stage.FRAGMENT;
        boolean output = direction.equals("out") || varying && stage == Stage.VERTEX;
        return new InterfaceType(type, interpolation, input, output, typeSlots,
                input && stage.perVertexInputs() || output && stage == Stage.TESS_CONTROL);
    }

    /** The type's name; {@code struct} for an inline struct. */
    private static String typeName(GlslTree tree, GlslTree.TypeSpec type) {
        return type.name() >= 0 ? tree.text(type.name()) : "struct";
    }

    /**
     * What one interface statement's declarators share: type, interpolation, direction, size, and
     * whether each is per-vertex: an input of a geometry or tessellation stage, or a control stage's output.
     */
    private record InterfaceType(String type, String interpolation, boolean input, boolean output, int slots,
                                 boolean perVertex) {}

    /**
     * The variables one interface statement declares, each with any array size, or null when a
     * declarator has an initializer, a size this cannot read, or a size besides the type's. The
     * size may sit on the type, as in Noble's {@code flat in vec3[9] skyIlluminanceCoefficients}.
     * A per-vertex input's declarator starts with its per-vertex dimension, sized or not
     * ({@code texcoord[]}, Rethinking Voxels' {@code texCoordV[3]}); the size is what follows it.
     */
    private static List<Declaration> declarators(GlslTree tree, GlslTree.Declaration declaration, int start, int end,
                                                 InterfaceType kind) {
        int typeLength = arrayLength(tree, declaration.type().arrays());
        if (typeLength < 0) return null;
        List<Declaration> declared = new ArrayList<>();
        for (GlslTree.Declarator declarator : declaration.declarators()) {
            List<GlslTree.Range> arrays = declarator.arrays();
            if (kind.perVertex()) {
                if (arrays.isEmpty()) return null;
                arrays = arrays.subList(1, arrays.size());
            }
            int length = arrayLength(tree, arrays);
            if (typeLength > 0 && length != 0) return null;
            length = Math.max(length, typeLength);
            if (length < 0 || declarator.initializer() != null) return null;
            String name = tree.text(declarator.name());
            boolean referenced = referencedOutsideDeclaration(tree.tokens(), name, start, end);
            declared.add(new Declaration(name, kind.type(), kind.interpolation(), kind.input(), kind.output(),
                    referenced, length, kind.slots() * Math.max(length, 1), kind.perVertex()));
        }
        return List.copyOf(declared);
    }

    /**
     * The length of a type's or a declarator's array sizes: 0 without one, the positive value of
     * one {@code [N]} whose N is a constant expression of literals ({@link GlslIntConstant}), or -1
     * for anything else (a named or unsized length, more than one dimension).
     */
    private static int arrayLength(GlslTree tree, List<GlslTree.Range> arrays) {
        if (arrays.isEmpty()) return 0;
        if (arrays.size() > 1) return -1;
        GlslTree.Range array = arrays.get(0);
        OptionalInt length = GlslIntConstant.value(tree.tokens(), array.start() + 1, array.end() - 1);
        return length.isPresent() && length.getAsInt() > 0 ? length.getAsInt() : -1;
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
                int length = arrayLength(tree, declarator.arrays());
                if (length < 0 || declarator.initializer() != null) return null;
                total += typeSlots * Math.max(length, 1);
            }
        }
        return total;
    }

    private static Map<String, Integer> builtinSlots() {
        Map<String, Integer> slots = new HashMap<>();
        // 16-bit types take the locations of the 32-bit types they widen to (GlslVendorExtensions).
        for (String scalar : List.of("float", "int", "uint", "float16_t", "int16_t", "uint16_t")) {
            slots.put(scalar, 1);
        }
        for (int size = 2; size <= 4; size++) {
            for (String vector : List.of("vec", "ivec", "uvec", "f16vec", "i16vec", "u16vec")) {
                slots.put(vector + size, 1);
            }
            slots.put("mat" + size, size);
            slots.put("f16mat" + size, size);
            for (int rows = 2; rows <= 4; rows++) {
                slots.put("mat" + size + "x" + rows, size);
                slots.put("f16mat" + size + "x" + rows, size);
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
