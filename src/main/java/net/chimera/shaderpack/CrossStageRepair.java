package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Iris's cross-stage repair (CompatibilityTransformer.transformGrouped), applied to each pair of
 * adjacent stages: the earlier stage, from the inputs the later stage declares. For each numeric
 * input, gl_ names aside, that is not an array (beyond the one dimension, one element per vertex,
 * that a tessellation or geometry stage's input takes):
 * <ul>
 *   <li>No output of its name, and the later stage reads it: the earlier stage gains an output with
 *   the input's qualifiers and element type, set to zero at the start of main
 *   (VARYING_UNWRITTEN_ZEROED). Bloop's overworld shadow writes lmtexcoord without declaring it.</li>
 *   <li>An output of its type that the earlier stage never names again: the output is set to zero at
 *   the start of main (VARYING_UNASSIGNED_ZEROED).</li>
 *   <li>A non-array output of another type of the same kind (scalar, vector, matrix): the output
 *   takes the input's type, and the earlier stage writes a global of the old type that the end of
 *   main converts into it (VARYING_TYPE_CONVERTED).</li>
 * </ul>
 * An output array, or an output of another kind, stays as written; the stage match refuses it,
 * as Iris's link does. So do the edits Iris makes that would not compile or would never reach a
 * vertex: a tessellation control stage's outputs are arrays, which the outputs Iris adds are not,
 * and a geometry stage would convert a retyped output after its last EmitVertex.
 */
final class CrossStageRepair {
    private CrossStageRepair() {}

    static final String UNWRITTEN_ZEROED = "VARYING_UNWRITTEN_ZEROED:";
    static final String UNASSIGNED_ZEROED = "VARYING_UNASSIGNED_ZEROED:";
    static final String TYPE_CONVERTED = "VARYING_TYPE_CONVERTED:";

    private static final Pattern SCALAR = Pattern.compile("float|int|uint|bool");
    private static final Pattern VECTOR = Pattern.compile("[iub]?vec[234]");
    private static final Pattern MATRIX = Pattern.compile("mat[234](?:x[234])?");

    /** The repaired source of the earlier stage, and one deviation per name it repaired. */
    record Result(String earlier, List<String> deviations) {}

    /** One interface variable: the declaration and declarator that make it, its name and type. */
    private record Variable(GlslTree.Declaration declaration, GlslTree.Declarator declarator, String name,
                            String type) {
        /** Whether the variable is an array beyond the one dimension a per-vertex input takes. */
        boolean array(boolean perVertex) {
            int dimensions = declarator.arrays().size() + declaration.type().arrays().size();
            return dimensions > (perVertex ? 1 : 0);
        }
    }

    /** The repair of a program's vertex stage from its fragment stage, with nothing between them. */
    static Result repair(String vertex, String fragment) {
        return repair(vertex, GlslInterfaceScanner.Stage.VERTEX, fragment, GlslInterfaceScanner.Stage.FRAGMENT);
    }

    /** The repair of {@code earlier} from the inputs of {@code later}, the stage GL links after it. */
    static Result repair(String earlier, GlslInterfaceScanner.Stage earlierStage, String later,
                         GlslInterfaceScanner.Stage laterStage) {
        Result unchanged = new Result(earlier, List.of());
        if (earlier == null || later == null || earlierStage == GlslInterfaceScanner.Stage.TESS_CONTROL) {
            return unchanged;
        }
        GlslTree earlierTree = GlslDeclarations.parseOrNull(earlier);
        GlslTree laterTree = GlslDeclarations.parseOrNull(later);
        GlslTree.Function main = earlierTree == null || laterTree == null
                ? null : GlslDeclarations.mainDefinition(earlierTree);
        if (main == null) return unchanged;
        Map<String, Variable> outputs = variables(earlierTree, Set.of("out", "varying"));
        Edits edits = new Edits(earlierTree, outputStorage(earlierTree, outputs.values(), earlier));
        boolean converts = earlierStage != GlslInterfaceScanner.Stage.GEOMETRY;
        for (Variable input : variables(laterTree, Set.of("in", "varying")).values()) {
            if (!input.name().startsWith("gl_") && kind(input.type()) >= 0
                    && !input.array(laterStage.perVertexInputs())) {
                repairInput(edits, earlierTree, laterTree, input, outputs.get(input.name()), converts);
            }
        }
        return edits.result(main);
    }

    /** Iris's repair of one input against the earlier stage's output of its name, null when none. */
    private static void repairInput(Edits edits, GlslTree earlierTree, GlslTree laterTree, Variable input,
                                    Variable output, boolean converts) {
        if (output == null) {
            if (referenced(laterTree, input)) edits.declare(laterTree, input);
        } else if (output.array(false)) {
            return;
        } else if (output.type().equals(input.type())) {
            if (!referenced(earlierTree, output)) edits.zeroUnassigned(output);
        } else if (converts && kind(output.type()) == kind(input.type())) {
            edits.convert(output, input.type());
        }
    }

    /** The file-scope interface variables whose storage is one of {@code storages}, by name. */
    private static Map<String, Variable> variables(GlslTree tree, Set<String> storages) {
        Map<String, Variable> result = new LinkedHashMap<>();
        for (GlslTree.External external : tree.externals()) {
            if (!(external instanceof GlslTree.Declaration declaration) || declaration.type() == null
                    || declaration.type().name() < 0 || storage(tree, declaration, storages) == null) {
                continue;
            }
            String type = tree.text(declaration.type().name());
            for (GlslTree.Declarator declarator : declaration.declarators()) {
                result.putIfAbsent(tree.text(declarator.name()),
                        new Variable(declaration, declarator, tree.text(declarator.name()), type));
            }
        }
        return result;
    }

    /** The declaration's storage qualifier when it is one of {@code storages}; null otherwise. */
    private static GlslTree.Qualifier storage(GlslTree tree, GlslTree.Declaration declaration, Set<String> storages) {
        for (GlslTree.Qualifier qualifier : declaration.qualifiers()) {
            if (storages.contains(tree.text(qualifier.keyword()))) return qualifier;
        }
        return null;
    }

    /** Whether the source names the variable outside its declaration, other than as a field. */
    private static boolean referenced(GlslTree tree, Variable variable) {
        List<GlslLexer.Token> tokens = tree.tokens();
        for (int index = 0; index < tokens.size(); index++) {
            if (variable.declaration().range().contains(index) || !tokens.get(index).identifier(variable.name())) {
                continue;
            }
            int previous = GlslLexer.previousSignificant(tokens, index);
            if (previous < 0 || !tokens.get(previous).symbol(".")) return true;
        }
        return false;
    }

    /** 0 for a scalar, 1 for a vector, 2 for a matrix, -1 for any other type (Iris's dimension). */
    private static int kind(String type) {
        if (SCALAR.matcher(type).matches()) return 0;
        if (VECTOR.matcher(type).matches()) return 1;
        return MATRIX.matcher(type).matches() ? 2 : -1;
    }

    /**
     * The storage an added output takes: the earlier stage's own outputs' storage, or for a stage that
     * declares none, {@code varying} before GLSL 1.30 and {@code out} from it. A modern post vertex
     * whose version line the normalizer dropped keeps its {@code out}.
     */
    private static String outputStorage(GlslTree tree, Collection<Variable> outputs, String source) {
        for (Variable output : outputs) {
            GlslTree.Qualifier storage = storage(tree, output.declaration(), Set.of("out", "varying"));
            if (storage != null) return tree.text(storage.keyword());
        }
        GlslVersion version = GlslVersion.first(source);
        return version == null || version.number() < 130 ? "varying" : "out";
    }

    /**
     * A numeric type's zero. LegacyShaderNormalizer starts referenced outputs at the same
     * statement, so it adds none beside a repaired one.
     */
    static String zero(String type) {
        return type + "(0)";
    }

    /** A retyped output: the input's type it takes, and the global of its old type the stage writes. */
    private record Retype(String type, String internal) {}

    /** The edits the repair makes to the earlier stage's source, applied in one print. */
    private static final class Edits {
        private final GlslTree tree;
        private final String outputStorage;
        private final StringBuilder declarations = new StringBuilder();
        private final StringBuilder prologue = new StringBuilder();
        private final StringBuilder epilogue = new StringBuilder();
        private final Map<GlslTree.Declaration, Map<String, Retype>> retyped = new LinkedHashMap<>();
        private final List<GlslTree.Edit> renames = new ArrayList<>();
        private final List<String> deviations = new ArrayList<>();

        Edits(GlslTree tree, String outputStorage) {
            this.tree = tree;
            this.outputStorage = outputStorage;
        }

        /** An output for an input the earlier stage does not declare, zero until that stage writes it. */
        void declare(GlslTree fragmentTree, Variable input) {
            GlslTree.Qualifier storage = storage(fragmentTree, input.declaration(), Set.of("in", "varying"));
            for (GlslTree.Qualifier qualifier : input.declaration().qualifiers()) {
                declarations.append(qualifier == storage ? outputStorage : fragmentTree.text(qualifier.range()))
                        .append(' ');
            }
            declarations.append(input.type()).append(' ').append(input.name()).append(";\n");
            initialize(input);
            deviations.add(UNWRITTEN_ZEROED + input.name());
        }

        /** An output the earlier stage declares but never names again starts main at zero. */
        void zeroUnassigned(Variable output) {
            initialize(output);
            deviations.add(UNASSIGNED_ZEROED + output.name());
        }

        private void initialize(Variable variable) {
            prologue.append(variable.name()).append(" = ").append(zero(variable.type())).append(";\n");
        }

        /** The output takes the input's type; the earlier stage writes a global of its old type instead. */
        void convert(Variable output, String inputType) {
            String internal = GlslTokenRewriter.uniqueIdentifier(tree.source(), "chimeraVarying_" + output.name());
            retyped.computeIfAbsent(output.declaration(), key -> new LinkedHashMap<>())
                    .put(output.name(), new Retype(inputType, internal));
            List<GlslLexer.Token> tokens = tree.tokens();
            for (int index = 0; index < tokens.size(); index++) {
                if (output.declaration().range().contains(index) || !tokens.get(index).identifier(output.name())) {
                    continue;
                }
                GlslTree.Binding binding = tree.resolve(index);
                if (binding != null && binding.node() == output.declarator()) {
                    renames.add(new GlslTree.Edit(new GlslTree.Range(index, index + 1), internal));
                }
            }
            boolean widened = kind(inputType) == 1 && size(output.type()) < size(inputType);
            epilogue.append(output.name()).append(" = ").append(inputType).append('(').append(internal)
                    .append(widened ? ", vec4(0)" : "").append(");\n");
            deviations.add(TYPE_CONVERTED + output.name());
        }

        Result result(GlslTree.Function main) {
            if (deviations.isEmpty()) return new Result(tree.source(), List.of());
            List<GlslTree.Edit> edits = new ArrayList<>(renames);
            retyped.forEach((declaration, types) -> edits.add(
                    new GlslTree.Edit(declaration.range(), redeclared(declaration, types))));
            if (!declarations.isEmpty()) {
                int first = firstDeclaration();
                edits.add(new GlslTree.Edit(new GlslTree.Range(first, first), declarations.toString()));
            }
            GlslTree.Range body = main.body().range();
            if (!prologue.isEmpty()) {
                edits.add(new GlslTree.Edit(new GlslTree.Range(body.start() + 1, body.start() + 1),
                        "\n" + prologue));
            }
            if (!epilogue.isEmpty()) {
                edits.add(new GlslTree.Edit(new GlslTree.Range(body.end() - 1, body.end() - 1), epilogue.toString()));
            }
            return new Result(tree.print(edits), List.copyOf(deviations));
        }

        /**
         * The declaration again, one statement per name: a retyped name with the input's type
         * after the global of its old type, every other name as written.
         */
        private String redeclared(GlslTree.Declaration declaration, Map<String, Retype> types) {
            String qualifiers = tree.text(new GlslTree.Range(declaration.qualifiers().get(0).range().start(),
                    declaration.type().range().start()));
            String oldType = tree.text(declaration.type().range());
            StringBuilder result = new StringBuilder();
            for (GlslTree.Declarator declarator : declaration.declarators()) {
                String name = tree.text(declarator.name());
                Retype retype = types.get(name);
                if (retype == null) {
                    result.append(qualifiers).append(oldType).append(' ').append(tree.text(declarator.range()));
                } else {
                    result.append(oldType).append(' ').append(retype.internal()).append(";\n")
                            .append(qualifiers).append(retype.type()).append(' ').append(name);
                }
                result.append(";\n");
            }
            return result.toString();
        }

        /** The token of the first file-scope declaration after the directives (Iris BEFORE_DECLARATIONS). */
        private int firstDeclaration() {
            for (GlslTree.External external : tree.externals()) {
                if (!(external instanceof GlslTree.Directive)) return external.range().start();
            }
            return tree.tokens().size();
        }

        private static int size(String vector) {
            return vector.charAt(vector.length() - 1) - '0';
        }
    }
}
