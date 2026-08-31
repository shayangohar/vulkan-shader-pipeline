package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Token-based scan of the small cross-stage interface supported by Chimera. */
public final class GlslInterfaceScanner {
    private static final Set<String> VARYING_TYPES = Set.of(
            "float", "int", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4");

    private GlslInterfaceScanner() {}

    public record Declaration(
            String name,
            String type,
            String qualifier,
            boolean input,
            boolean output,
            boolean referenced
    ) {}

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

    public static StageInterface scan(String source, boolean vertexStage) {
        List<GlslLexer.Token> tokens = GlslLexer.lex(source);
        List<Declaration> inputs = new ArrayList<>();
        List<Declaration> outputs = new ArrayList<>();
        List<String> deviations = new ArrayList<>();
        Map<String, Declaration> declarations = new LinkedHashMap<>();
        int braceDepth = 0;
        for (int index = 0; index < tokens.size(); index++) {
            GlslLexer.Token token = tokens.get(index);
            if (token.symbol("{")) {
                braceDepth++;
                continue;
            }
            if (token.symbol("}")) {
                braceDepth = Math.max(0, braceDepth - 1);
                continue;
            }
            if (braceDepth != 0 || token.kind() != GlslLexer.Kind.IDENTIFIER) {
                continue;
            }
            int qualifierIndex = index;
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
            if (typeIndex < 0 || tokens.get(typeIndex).kind() != GlslLexer.Kind.IDENTIFIER) {
                deviations.add("PROGRAM_INTERFACE_DECLARATION_UNSUPPORTED");
                continue;
            }
            String type = tokens.get(typeIndex).text();
            if (!VARYING_TYPES.contains(type)) {
                deviations.add("PROGRAM_VARYING_TYPE_UNSUPPORTED:" + type);
                continue;
            }
            int statementEnd = findStatementEnd(tokens, typeIndex);
            if (statementEnd <= typeIndex || statementEnd > tokens.size()) {
                deviations.add("PROGRAM_INTERFACE_DECLARATION_UNSUPPORTED");
                continue;
            }
            boolean input = qualifierToken.identifier("in")
                    || (qualifierToken.identifier("varying") && !vertexStage);
            boolean output = qualifierToken.identifier("out")
                    || (qualifierToken.identifier("varying") && vertexStage);
            int nameIndex = GlslLexer.nextSignificant(tokens, typeIndex);
            boolean parsedName = false;
            while (nameIndex >= 0 && nameIndex < statementEnd - 1) {
                if (tokens.get(nameIndex).kind() != GlslLexer.Kind.IDENTIFIER) {
                    deviations.add("PROGRAM_INTERFACE_DECLARATION_UNSUPPORTED");
                    break;
                }
                int separator = GlslLexer.nextSignificant(tokens, nameIndex);
                if (separator < 0 || separator >= statementEnd
                        || (!tokens.get(separator).symbol(",") && !tokens.get(separator).symbol(";"))) {
                    deviations.add("PROGRAM_INTERFACE_DECLARATION_UNSUPPORTED");
                    break;
                }
                String name = tokens.get(nameIndex).text();
                boolean referenced = referencedOutsideDeclaration(tokens, name, index, statementEnd);
                Declaration declaration = new Declaration(name, type, interpolation, input, output, referenced);
                Declaration previous = declarations.putIfAbsent(name, declaration);
                if (previous != null && (!previous.type().equals(type)
                        || previous.input() != input || previous.output() != output)) {
                    deviations.add("PROGRAM_INTERFACE_CONFLICT:" + name);
                } else {
                    if (input) {
                        inputs.add(declaration);
                    }
                    if (output) {
                        outputs.add(declaration);
                    }
                }
                parsedName = true;
                if (tokens.get(separator).symbol(";")) {
                    break;
                }
                nameIndex = GlslLexer.nextSignificant(tokens, separator);
            }
            if (parsedName) {
                index = Math.max(index, statementEnd - 1);
            }
        }
        Map<String, Integer> locations = new TreeMap<>();
        int location = 0;
        for (String name : declarations.keySet().stream().sorted().toList()) {
            locations.put(name, location++);
        }
        return new StageInterface(inputs, outputs, locations, deviations);
    }

    public static ProgramMatch match(StageInterface vertex, StageInterface fragment) {
        List<String> deviations = new ArrayList<>();
        Map<String, Integer> locations = new TreeMap<>();
        if (vertex == null || fragment == null) {
            return new ProgramMatch(locations, List.of("PROGRAM_INTERFACE_STAGE_MISSING"));
        }
        for (Declaration input : fragment.inputs()) {
            Declaration output = vertex.output(input.name());
            if (output == null) {
                if (input.referenced()) {
                    deviations.add("PROGRAM_VARYING_UNMATCHED:" + input.name());
                }
                continue;
            }
            if (!output.type().equals(input.type())) {
                deviations.add("PROGRAM_INTERFACE_CONFLICT:" + input.name());
                continue;
            }
            locations.put(input.name(), locations.size());
        }
        return new ProgramMatch(locations, deviations);
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

    private static boolean isInterpolation(String value) {
        return value.equals("flat") || value.equals("noperspective")
                || value.equals("smooth") || value.equals("centroid") || value.equals("sample");
    }
}
