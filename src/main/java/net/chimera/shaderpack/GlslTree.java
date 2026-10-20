package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    private final String source;
    private final List<GlslLexer.Token> tokens;
    private final List<External> externals;
    private int[] offsets;

    private GlslTree(String source, List<GlslLexer.Token> tokens, List<External> externals) {
        this.source = source;
        this.tokens = tokens;
        this.externals = externals;
    }

    /** The parsed text, as given. */
    String source() {
        return source;
    }

    /**
     * The latest trees. A program's conversion steps each parse the source they are handed, and
     * many hand on the same text unchanged.
     */
    private static final Map<String, GlslTree> RECENT = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, GlslTree> eldest) {
            return size() > 8;
        }
    };

    /** Parses {@code source}; throws {@link GlslParser.ParseFailure} naming the line it stopped at. */
    static GlslTree parse(String source) {
        String input = source == null ? "" : source;
        synchronized (RECENT) {
            GlslTree known = RECENT.get(input);
            if (known != null) return known;
        }
        List<GlslLexer.Token> tokens = GlslLexer.lex(input);
        GlslTree tree = new GlslTree(input, tokens, new GlslParser(tokens).unit());
        synchronized (RECENT) {
            RECENT.put(input, tree);
        }
        return tree;
    }

    /** The character offset where token {@code token} starts; the source length for {@code tokens().size()}. */
    synchronized int offset(int token) {
        if (offsets == null) {
            int[] computed = new int[tokens.size() + 1];
            for (int index = 0; index < tokens.size(); index++) {
                computed[index + 1] = computed[index] + tokens.get(index).text().length();
            }
            offsets = computed;
        }
        return offsets[token];
    }

    List<GlslLexer.Token> tokens() {
        return tokens;
    }

    List<External> externals() {
        return externals;
    }

    /** Every directive, at file scope and inside function bodies, in source order. */
    List<Directive> directives() {
        List<Directive> directives = new ArrayList<>();
        for (External external : externals) {
            if (external instanceof Directive directive) directives.add(directive);
            if (external instanceof Function function && function.body() != null) {
                collectDirectives(function.body(), directives);
            }
        }
        return directives;
    }

    private static void collectDirectives(Statement statement, List<Directive> directives) {
        switch (statement) {
            case Directive directive -> directives.add(directive);
            case Block block -> block.statements().forEach(inner -> collectDirectives(inner, directives));
            case Control control -> control.bodies().forEach(body -> collectDirectives(body, directives));
            default -> { }
        }
    }

    /** Which tokens belong to a directive: {@code directive[token]} for every token. */
    boolean[] directiveTokens() {
        boolean[] directive = new boolean[tokens.size()];
        for (Directive value : directives()) {
            for (int index = value.range().start(); index < value.range().end(); index++) directive[index] = true;
        }
        return directive;
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

    /** The source as written: every token, trivia included. */
    String print() {
        return print(List.of());
    }

    /**
     * The source with each edit's range replaced by its text. An empty range inserts its text
     * before the token it starts at, or at the end of the source; several insertions at one
     * position keep their given order, ahead of a replacement starting there. Edits must not
     * overlap.
     */
    String print(List<Edit> edits) {
        List<Edit> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt((Edit edit) -> edit.range().start())
                .thenComparingInt(edit -> edit.range().end()));
        StringBuilder result = new StringBuilder();
        int at = 0;
        for (Edit edit : ordered) {
            if (edit.range().start() < at) throw new IllegalArgumentException("edits overlap");
            while (at < edit.range().start()) result.append(tokens.get(at++).text());
            result.append(edit.text());
            at = edit.range().end();
        }
        while (at < tokens.size()) result.append(tokens.get(at++).text());
        return result.toString();
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
