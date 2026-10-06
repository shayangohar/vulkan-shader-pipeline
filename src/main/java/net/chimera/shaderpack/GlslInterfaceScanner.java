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
 * Token-based scan of a stage's cross-stage interface: every {@code in}, {@code out} and
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
            return deviations.isEmpty();
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

    private static Parse parse(String source, boolean vertexStage) {
        List<GlslLexer.Token> tokens = GlslLexer.lex(source);
        Map<String, Integer> structSlots = structSlots(tokens);
        List<Statement> statements = new ArrayList<>();
        List<String> deviations = new ArrayList<>();
        int braceDepth = 0;
        int parenDepth = 0;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("#") && atLineStart(tokens, index)) {
                // A directive is not a declaration: `#define attribute in` must not read the
                // next line's `const` as a varying type.
                index = endOfLine(tokens, index);
                continue;
            }
            if (token.symbol("{")) {
                braceDepth++;
                continue;
            }
            if (token.symbol("}")) {
                braceDepth = Math.max(0, braceDepth - 1);
                continue;
            }
            if (braceDepth == 0 && token.symbol("(")) {
                parenDepth++;
                continue;
            }
            if (braceDepth == 0 && token.symbol(")")) {
                parenDepth = Math.max(0, parenDepth - 1);
                continue;
            }
            // Parameter lists are not program interfaces, whatever qualifiers precede `in`
            // (Bliss: float linearizeDepthFast(const in float depth, ...)).
            if (braceDepth != 0 || parenDepth != 0 || token.kind() != GlslLexer.Kind.IDENTIFIER) {
                continue;
            }
            int qualifierIndex = index;
            if (token.identifier("layout")) {
                int open = GlslLexer.nextSignificant(tokens, index);
                int close = open < 0 ? -1 : GlslLexer.matching(tokens, open, "(", ")");
                if (open < 0 || close < 0) {
                    deviations.add("PROGRAM_INTERFACE_DECLARATION_UNSUPPORTED");
                    continue;
                }
                qualifierIndex = GlslLexer.nextSignificant(tokens, close);
                if (qualifierIndex < 0) {
                    continue;
                }
            }
            String interpolation = null;
            if (isInterpolation(tokens.get(qualifierIndex).text())) {
                interpolation = tokens.get(qualifierIndex).text();
                qualifierIndex = GlslLexer.nextSignificant(tokens, qualifierIndex);
                if (qualifierIndex < 0) {
                    continue;
                }
            }
            GlslLexer.Token qualifierToken = tokens.get(qualifierIndex);
            if (!qualifierToken.identifier("varying")
                    && !qualifierToken.identifier("in")
                    && !qualifierToken.identifier("out")) {
                continue;
            }
            int typeIndex = GlslLexer.nextSignificant(tokens, qualifierIndex);
            while (typeIndex >= 0 && isPrecision(tokens.get(typeIndex).text())) {
                typeIndex = GlslLexer.nextSignificant(tokens, typeIndex);
            }
            if (typeIndex < 0 || tokens.get(typeIndex).kind() != GlslLexer.Kind.IDENTIFIER) {
                deviations.add("PROGRAM_INTERFACE_DECLARATION_UNSUPPORTED");
                continue;
            }
            String type = tokens.get(typeIndex).text();
            Integer typeSlots = BUILTIN_SLOTS.containsKey(type) ? BUILTIN_SLOTS.get(type) : structSlots.get(type);
            if (typeSlots == null) {
                deviations.add("PROGRAM_VARYING_TYPE_UNSUPPORTED:" + type);
                continue;
            }
            int statementEnd = findStatementEnd(tokens, typeIndex);
            boolean input = qualifierToken.identifier("in")
                    || (qualifierToken.identifier("varying") && !vertexStage);
            boolean output = qualifierToken.identifier("out")
                    || (qualifierToken.identifier("varying") && vertexStage);
            List<Declaration> declared = new ArrayList<>();
            boolean malformed = false;
            int nameIndex = GlslLexer.nextSignificant(tokens, typeIndex);
            while (nameIndex >= 0 && nameIndex < statementEnd - 1) {
                if (tokens.get(nameIndex).kind() != GlslLexer.Kind.IDENTIFIER) {
                    malformed = true;
                    break;
                }
                String name = tokens.get(nameIndex).text();
                int separator = GlslLexer.nextSignificant(tokens, nameIndex);
                int arraySize = 0;
                if (separator >= 0 && tokens.get(separator).symbol("[")) {
                    int size = GlslLexer.nextSignificant(tokens, separator);
                    int close = size < 0 ? -1 : GlslLexer.nextSignificant(tokens, size);
                    arraySize = size < 0 ? -1 : arraySize(tokens.get(size));
                    if (arraySize <= 0 || close < 0 || !tokens.get(close).symbol("]")) {
                        malformed = true;
                        break;
                    }
                    separator = GlslLexer.nextSignificant(tokens, close);
                }
                if (separator < 0 || separator >= statementEnd
                        || (!tokens.get(separator).symbol(",") && !tokens.get(separator).symbol(";"))) {
                    malformed = true;
                    break;
                }
                boolean referenced = referencedOutsideDeclaration(tokens, name, index, statementEnd);
                declared.add(new Declaration(name, type, interpolation, input, output, referenced,
                        arraySize, typeSlots * Math.max(arraySize, 1)));
                if (tokens.get(separator).symbol(";")) {
                    break;
                }
                nameIndex = GlslLexer.nextSignificant(tokens, separator);
            }
            if (malformed || declared.isEmpty()) {
                deviations.add("PROGRAM_INTERFACE_DECLARATION_UNSUPPORTED");
                continue;
            }
            statements.add(new Statement(index, statementEnd, List.copyOf(declared)));
            index = statementEnd - 1;
        }
        return new Parse(tokens, statements, deviations);
    }

    /**
     * Locations each file-scope {@code struct} occupies: the sum of its members, each a built-in
     * or an earlier struct, times any array length. A struct with a member this cannot size is
     * left out, so an interface variable of that type is reported unsupported.
     */
    private static Map<String, Integer> structSlots(List<GlslLexer.Token> tokens) {
        Map<String, Integer> result = new HashMap<>();
        int braceDepth = 0;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("{")) {
                braceDepth++;
            } else if (token.symbol("}")) {
                braceDepth = Math.max(0, braceDepth - 1);
            }
            if (braceDepth != 0 || !token.identifier("struct")) {
                continue;
            }
            int nameIndex = GlslLexer.nextSignificant(tokens, index);
            int open = nameIndex < 0 ? -1 : GlslLexer.nextSignificant(tokens, nameIndex);
            if (open < 0 || tokens.get(nameIndex).kind() != GlslLexer.Kind.IDENTIFIER
                    || !tokens.get(open).symbol("{")) {
                continue;
            }
            int close = GlslLexer.matching(tokens, open, "{", "}");
            if (close < 0) {
                continue;
            }
            Integer slots = memberSlots(tokens, open + 1, close, result);
            if (slots != null && slots > 0) {
                result.put(tokens.get(nameIndex).text(), slots);
            }
            index = close - 1;
        }
        return result;
    }

    /** Sum of member locations in {@code [start, end)}, or null for a member this cannot size. */
    private static Integer memberSlots(List<GlslLexer.Token> tokens, int start, int end,
                                       Map<String, Integer> structs) {
        int total = 0;
        int index = start;
        while (true) {
            int typeIndex = significantAtOrAfter(tokens, index, end);
            if (typeIndex < 0) {
                return total;
            }
            while (typeIndex >= 0 && isPrecision(tokens.get(typeIndex).text())) {
                typeIndex = GlslLexer.nextSignificant(tokens, typeIndex);
            }
            if (typeIndex < 0 || typeIndex >= end) {
                return null;
            }
            String type = tokens.get(typeIndex).text();
            Integer typeSlots = BUILTIN_SLOTS.containsKey(type) ? BUILTIN_SLOTS.get(type) : structs.get(type);
            if (typeSlots == null) {
                return null;
            }
            int cursor = GlslLexer.nextSignificant(tokens, typeIndex);
            while (true) {
                if (cursor < 0 || cursor >= end || tokens.get(cursor).kind() != GlslLexer.Kind.IDENTIFIER) {
                    return null;
                }
                int separator = GlslLexer.nextSignificant(tokens, cursor);
                int length = 1;
                if (separator >= 0 && tokens.get(separator).symbol("[")) {
                    int size = GlslLexer.nextSignificant(tokens, separator);
                    int close = size < 0 ? -1 : GlslLexer.nextSignificant(tokens, size);
                    length = size < 0 ? -1 : arraySize(tokens.get(size));
                    if (length <= 0 || close < 0 || !tokens.get(close).symbol("]")) {
                        return null;
                    }
                    separator = GlslLexer.nextSignificant(tokens, close);
                }
                if (separator < 0 || separator >= end) {
                    return null;
                }
                total += typeSlots * length;
                if (tokens.get(separator).symbol(";")) {
                    index = separator + 1;
                    break;
                }
                if (!tokens.get(separator).symbol(",")) {
                    return null;
                }
                cursor = GlslLexer.nextSignificant(tokens, separator);
            }
        }
    }

    private static int significantAtOrAfter(List<GlslLexer.Token> tokens, int index, int end) {
        for (int current = index; current < end; current++) {
            if (tokens.get(current).significant()) {
                return current;
            }
        }
        return -1;
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

    private static int findStatementEnd(List<GlslLexer.Token> tokens, int start) {
        for (int index = start; index < tokens.size(); index++) {
            if (tokens.get(index).symbol(";")) {
                return index + 1;
            }
        }
        return tokens.size();
    }

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

    private static boolean atLineStart(List<GlslLexer.Token> tokens, int index) {
        for (int previous = index - 1; previous >= 0; previous--) {
            GlslLexer.Token token = tokens.get(previous);
            if (token.kind() != GlslLexer.Kind.TRIVIA) {
                return false;
            }
            if (token.text().indexOf('\n') >= 0) {
                return true;
            }
        }
        return true;
    }

    /** The last token of the directive line starting at {@code index}. */
    private static int endOfLine(List<GlslLexer.Token> tokens, int index) {
        for (int next = index + 1; next < tokens.size(); next++) {
            GlslLexer.Token token = tokens.get(next);
            if (token.kind() == GlslLexer.Kind.TRIVIA && token.text().indexOf('\n') >= 0) {
                return next;
            }
        }
        return tokens.size() - 1;
    }

    private static boolean isInterpolation(String value) {
        return value.equals("flat") || value.equals("noperspective")
                || value.equals("smooth") || value.equals("centroid") || value.equals("sample");
    }

    private static boolean isPrecision(String value) {
        return value.equals("lowp") || value.equals("mediump") || value.equals("highp");
    }
}
