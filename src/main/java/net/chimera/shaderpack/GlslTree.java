package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A lossless parse of one GLSL source over {@link GlslLexer} tokens (KNOW-714). Every node names a
 * range of token indices, so comments and whitespace stay in the token list and an unedited region
 * prints exactly as written. The tree reaches statements: a function body holds blocks, control
 * statements and local declarations, which is enough to resolve a name through its scopes.
 * Expressions stay token ranges until a pass needs more. Pure Java, with no host types.
 */
final class GlslTree {
    /** Token indices {@code [start, end)}; a node's range starts and ends on a significant token. */
    record Range(int start, int end) {
        boolean contains(int token) {
            return token >= start && token < end;
        }
    }

    sealed interface Node permits External, Statement, Qualifier, TypeSpec, Struct, Declarator, Parameter {
        Range range();
    }

    /** What a source holds at file scope. */
    sealed interface External extends Node permits Directive, Declaration, InterfaceBlock, Function, Empty {}

    /** What a function body holds. */
    sealed interface Statement extends Node permits Directive, Declaration, Block, Control, Simple {}

    /** A preprocessor line that reaches the compiler: {@code #version}, {@code #extension}, {@code #line}. */
    record Directive(Range range) implements External, Statement {}

    /** One qualifier keyword, or {@code layout(...)} with its parenthesized list. */
    record Qualifier(Range range, int keyword) implements Node {}

    /** A type name or struct definition, with any array sizes written after it. */
    record TypeSpec(Range range, int name, Struct struct, List<Range> arrays) implements Node {}

    /** {@code struct Name { members }}; {@code name} is -1 when anonymous. */
    record Struct(Range range, int name, List<Declaration> members) implements Node {}

    /** One name of a declarator list, with its array sizes and initializer (null without one). */
    record Declarator(Range range, int name, List<Range> arrays, Range initializer) implements Node {}

    /**
     * Qualifiers, a type and declarators: a variable list, a struct definition, a precision
     * statement, or a qualifier-only line ({@code layout(local_size_x = 8) in;}, {@code invariant
     * gl_Position;}), where {@code type} is null.
     */
    record Declaration(Range range, List<Qualifier> qualifiers, TypeSpec type, List<Declarator> declarators)
            implements External, Statement {}

    /** {@code uniform Name { members } instance;}; {@code instances} is empty for an unnamed block. */
    record InterfaceBlock(Range range, List<Qualifier> qualifiers, int name, List<Declaration> members,
                          List<Declarator> instances) implements External {}

    record Parameter(Range range, List<Qualifier> qualifiers, TypeSpec type, int name, List<Range> arrays)
            implements Node {}

    /** A function definition, or a prototype when {@code body} is null. */
    record Function(Range range, List<Qualifier> qualifiers, TypeSpec returnType, int name, Range parameterList,
                    List<Parameter> parameters, Block body) implements External {}

    /** A semicolon on its own at file scope. */
    record Empty(Range range) implements External {}

    record Block(Range range, List<Statement> statements) implements Statement {}

    /**
     * {@code if}, {@code for}, {@code while}, {@code do} or {@code switch}: its parenthesized
     * header, a for loop's init declaration (null otherwise), and its bodies (if: then, else).
     */
    record Control(Range range, int keyword, Range header, Declaration init, List<Statement> bodies)
            implements Statement {}

    /** Any other statement: an expression, a jump, a case label or an empty statement. */
    record Simple(Range range) implements Statement {}

    /** Replaces the tokens of {@code range} with {@code text} when printing. */
    record Edit(Range range, String text) {}

    /** The declaration a name resolves to: its declaring node and the token of the declared name. */
    record Binding(Node node, int name) {}

    private final List<GlslLexer.Token> tokens;
    private final List<External> externals;

    private GlslTree(List<GlslLexer.Token> tokens, List<External> externals) {
        this.tokens = tokens;
        this.externals = externals;
    }

    /** Parses {@code source}; throws {@link GlslParser.ParseFailure} naming the line it stopped at. */
    static GlslTree parse(String source) {
        List<GlslLexer.Token> tokens = GlslLexer.lex(source);
        return new GlslTree(tokens, new GlslParser(tokens).unit());
    }

    List<GlslLexer.Token> tokens() {
        return tokens;
    }

    List<External> externals() {
        return externals;
    }

    String text(int token) {
        return tokens.get(token).text();
    }

    String text(Range range) {
        StringBuilder result = new StringBuilder();
        for (int index = range.start(); index < range.end(); index++) result.append(tokens.get(index).text());
        return result.toString();
    }

    /** The 1-based line a token starts on. */
    int line(int token) {
        int line = 1;
        for (int index = 0; index < token && index < tokens.size(); index++) {
            line += (int) tokens.get(index).text().chars().filter(value -> value == '\n').count();
        }
        return line;
    }

    /** The source, walked node by node: what lies between two nodes is trivia, printed as it stands. */
    String print() {
        return print(List.of());
    }

    /** The source with each edit's range replaced by its text; edits must not overlap. */
    String print(List<Edit> edits) {
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(edit -> edit.range().start()));
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index).range().start() < ordered.get(index - 1).range().end()) {
                throw new IllegalArgumentException("edits overlap");
            }
        }
        StringBuilder result = new StringBuilder();
        int at = 0;
        int next = 0;
        for (External external : externals) {
            at = appendUpTo(result, at, external.range().start(), ordered, next);
            next = skipEdits(ordered, next, at);
            at = appendUpTo(result, at, external.range().end(), ordered, next);
            next = skipEdits(ordered, next, at);
        }
        appendUpTo(result, at, tokens.size(), ordered, next);
        return result.toString();
    }

    /** Appends tokens {@code [from, to)}, an edit's text in place of its range; returns where it stopped. */
    private int appendUpTo(StringBuilder result, int from, int to, List<Edit> edits, int next) {
        int at = from;
        int edit = next;
        while (at < to) {
            if (edit < edits.size() && edits.get(edit).range().start() == at) {
                Edit current = edits.get(edit++);
                result.append(current.text());
                at = current.range().end();
                continue;
            }
            result.append(tokens.get(at++).text());
        }
        return at;
    }

    private static int skipEdits(List<Edit> edits, int next, int at) {
        int edit = next;
        while (edit < edits.size() && edits.get(edit).range().end() <= at) edit++;
        return edit;
    }

    /**
     * The declaration the identifier at {@code token} names, as GLSL scopes resolve it: the
     * innermost block first, then a for loop's init, the function's parameters, and file scope.
     * A declaration counts from its name on. Null for a field access ({@code value.name}) and for
     * a name the source does not declare (a built-in).
     */
    Binding resolve(int token) {
        GlslLexer.Token target = tokens.get(token);
        if (target.kind() != GlslLexer.Kind.IDENTIFIER) return null;
        int previous = GlslLexer.previousSignificant(tokens, token);
        if (previous >= 0 && tokens.get(previous).symbol(".")) return null;
        String name = target.text();
        for (External external : externals) {
            if (external instanceof Function function && function.body() != null
                    && function.range().contains(token)) {
                Binding local = GlslScopes.inFunction(this, function, name, token);
                if (local != null) return local;
            }
        }
        return GlslScopes.atFileScope(this, name, token);
    }
}
