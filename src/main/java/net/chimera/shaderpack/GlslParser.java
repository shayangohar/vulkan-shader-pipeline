package net.chimera.shaderpack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Recursive-descent parser for {@link GlslTree}, over the significant tokens of a lexed source.
 * External declarations parse fully: qualifiers in any order, layout lists, types and structs,
 * declarator lists with arrays and initializers, interface blocks, and function signatures.
 * Bodies parse into statements, and expressions are skipped as balanced token runs.
 */
final class GlslParser {
    /** Why a source does not parse, with the line and token it stopped at. */
    static final class ParseFailure extends IllegalArgumentException {
        ParseFailure(String message) {
            super(message);
        }
    }

    private static final Set<String> QUALIFIERS = Set.of("const", "in", "out", "inout", "attribute", "uniform",
            "varying", "buffer", "shared", "centroid", "sample", "patch", "smooth", "flat", "noperspective",
            "invariant", "precise", "highp", "mediump", "lowp", "precision", "coherent", "volatile", "restrict",
            "readonly", "writeonly", "layout", "subroutine");
    private static final Set<String> STATEMENT_KEYWORDS = Set.of("return", "break", "continue", "discard",
            "else", "case", "default");
    private static final Pattern BUILT_IN_TYPE = Pattern.compile("void|bool|int|uint|float|double"
            + "|[biud]?vec[234]|d?mat[234](x[234])?|atomic_uint|[iu]?(sampler|image|texture|subpassInput)\\w*");

    private final List<GlslLexer.Token> tokens;
    private final int[] significant;
    private final Set<String> structNames = new HashSet<>();
    private int at;

    GlslParser(List<GlslLexer.Token> tokens) {
        this.tokens = tokens;
        this.significant = tokens.isEmpty() ? new int[0] : IntStream.range(0, tokens.size())
                .filter(index -> tokens.get(index).significant()).toArray();
    }

    /** Every external declaration, in order; together with the trivia between them, the whole source. */
    List<GlslTree.External> unit() {
        List<GlslTree.External> externals = new ArrayList<>();
        while (at < significant.length) externals.add(external());
        return List.copyOf(externals);
    }

    private GlslTree.External external() {
        if (symbol("#")) return directive();
        int start = at;
        if (symbol(";")) {
            at++;
            return new GlslTree.Empty(range(start));
        }
        List<GlslTree.Qualifier> qualifiers = qualifiers();
        if (identifier() && !text().equals("struct") && symbolAt(at + 1, "{") && !qualifiers.isEmpty()) {
            return interfaceBlock(start, qualifiers);
        }
        if (namesOnly(qualifiers)) return declaration(start, qualifiers, null);
        if (symbol(";")) {
            at++;
            return new GlslTree.Declaration(range(start), qualifiers, null, List.of());
        }
        GlslTree.TypeSpec type = typeSpec();
        if (identifier() && symbolAt(at + 1, "(")) return function(start, qualifiers, type);
        return declaration(start, qualifiers, type);
    }

    /** A preprocessor line, to the end of its line; the newline stays trivia. */
    private GlslTree.Directive directive() {
        int start = at;
        int token = significant[at];
        int last = token;
        for (int index = token + 1; index < tokens.size(); index++) {
            GlslLexer.Token value = tokens.get(index);
            if (!value.significant() && value.text().indexOf('\n') >= 0) break;
            if (value.significant()) last = index;
        }
        while (at < significant.length && significant[at] <= last) at++;
        return new GlslTree.Directive(range(start));
    }

    /** Qualifier keywords in any order, a {@code layout} or {@code subroutine} with its list. */
    private List<GlslTree.Qualifier> qualifiers() {
        List<GlslTree.Qualifier> qualifiers = new ArrayList<>();
        while (identifier() && QUALIFIERS.contains(text())) {
            int start = at++;
            if ((tokens.get(significant[start]).text().equals("layout")
                    || tokens.get(significant[start]).text().equals("subroutine")) && symbol("(")) {
                skipBalanced();
            }
            qualifiers.add(new GlslTree.Qualifier(range(start), significant[start]));
        }
        return qualifiers;
    }

    /** {@code invariant gl_Position;}: qualifiers then names, with no type. */
    private boolean namesOnly(List<GlslTree.Qualifier> qualifiers) {
        return !qualifiers.isEmpty() && identifier() && !isType(text())
                && (symbolAt(at + 1, ";") || symbolAt(at + 1, ","));
    }

    private boolean isType(String name) {
        return BUILT_IN_TYPE.matcher(name).matches() || structNames.contains(name) || name.equals("struct");
    }

    private GlslTree.TypeSpec typeSpec() {
        int start = at;
        GlslTree.Struct struct = identifier() && text().equals("struct") ? struct() : null;
        int name = struct == null ? expectIdentifier() : -1;
        List<GlslTree.Range> arrays = arrays();
        return new GlslTree.TypeSpec(range(start), name, struct, arrays);
    }

    private GlslTree.Struct struct() {
        int start = at++;
        int name = -1;
        if (identifier()) {
            name = significant[at++];
            structNames.add(tokens.get(name).text());
        }
        expect("{");
        List<GlslTree.Declaration> members = new ArrayList<>();
        while (!symbol("}")) members.add(member());
        at++;
        return new GlslTree.Struct(range(start), name, List.copyOf(members));
    }

    /** A struct or block member: qualifiers, a type and declarators. */
    private GlslTree.Declaration member() {
        int start = at;
        List<GlslTree.Qualifier> qualifiers = qualifiers();
        return declaration(start, qualifiers, typeSpec());
    }

    /** The declarators after {@code type} (none for a struct definition), through the semicolon. */
    private GlslTree.Declaration declaration(int start, List<GlslTree.Qualifier> qualifiers, GlslTree.TypeSpec type) {
        List<GlslTree.Declarator> declarators = symbol(";") ? List.of() : declarators();
        expect(";");
        return new GlslTree.Declaration(range(start), qualifiers, type, declarators);
    }

    private List<GlslTree.Declarator> declarators() {
        List<GlslTree.Declarator> declarators = new ArrayList<>();
        declarators.add(declarator());
        while (symbol(",")) {
            at++;
            declarators.add(declarator());
        }
        return List.copyOf(declarators);
    }

    private GlslTree.Declarator declarator() {
        int start = at;
        int name = expectIdentifier();
        List<GlslTree.Range> arrays = arrays();
        GlslTree.Range initializer = null;
        if (symbol("=")) {
            at++;
            initializer = skipExpression(",", ";");
        }
        return new GlslTree.Declarator(range(start), name, arrays, initializer);
    }

    private List<GlslTree.Range> arrays() {
        List<GlslTree.Range> arrays = new ArrayList<>();
        while (symbol("[")) {
            int start = at;
            skipBalanced();
            arrays.add(range(start));
        }
        return List.copyOf(arrays);
    }

    private GlslTree.InterfaceBlock interfaceBlock(int start, List<GlslTree.Qualifier> qualifiers) {
        int name = significant[at++];
        expect("{");
        List<GlslTree.Declaration> members = new ArrayList<>();
        while (!symbol("}")) members.add(member());
        at++;
        List<GlslTree.Declarator> instances = symbol(";") ? List.of() : declarators();
        expect(";");
        return new GlslTree.InterfaceBlock(range(start), qualifiers, name, List.copyOf(members), instances);
    }

    private GlslTree.Function function(int start, List<GlslTree.Qualifier> qualifiers, GlslTree.TypeSpec type) {
        int name = significant[at++];
        int open = at;
        expect("(");
        List<GlslTree.Parameter> parameters = new ArrayList<>();
        while (!symbol(")")) {
            parameters.add(parameter());
            if (symbol(",")) at++;
            else if (!symbol(")")) throw failure("expected , or ) in a parameter list");
        }
        at++;
        GlslTree.Range parameterList = range(open);
        GlslTree.Block body = null;
        if (symbol(";")) at++;
        else body = block();
        return new GlslTree.Function(range(start), qualifiers, type, name, parameterList,
                List.copyOf(parameters), body);
    }

    private GlslTree.Parameter parameter() {
        int start = at;
        List<GlslTree.Qualifier> qualifiers = qualifiers();
        GlslTree.TypeSpec type = typeSpec();
        int name = identifier() ? significant[at++] : -1;
        List<GlslTree.Range> arrays = arrays();
        return new GlslTree.Parameter(range(start), qualifiers, type, name, arrays);
    }

    private GlslTree.Block block() {
        int start = at;
        expect("{");
        List<GlslTree.Statement> statements = new ArrayList<>();
        while (!symbol("}")) statements.add(statement());
        at++;
        return new GlslTree.Block(range(start), List.copyOf(statements));
    }

    private GlslTree.Statement statement() {
        if (atEnd()) throw failure("the source ends inside a block");
        if (symbol("#")) return directive();
        if (symbol("{")) return block();
        if (identifier()) {
            switch (text()) {
                case "if" -> { return ifStatement(); }
                case "for" -> { return forStatement(); }
                case "while" -> { return whileStatement(); }
                case "do" -> { return doStatement(); }
                case "switch" -> { return switchStatement(); }
                case "case", "default" -> { return label(); }
                default -> { }
            }
            if (startsDeclaration()) return localDeclaration();
        }
        return simple();
    }

    /** Whether a declaration starts here: qualifiers, a type, any array sizes, then a name. */
    private boolean startsDeclaration() {
        int saved = at;
        try {
            qualifiers();
            if (!identifier() || STATEMENT_KEYWORDS.contains(text())) return false;
            if (text().equals("struct")) return true;
            at++;
            while (symbol("[")) skipBalanced();
            return identifier();
        } finally {
            at = saved;
        }
    }

    private GlslTree.Declaration localDeclaration() {
        int start = at;
        List<GlslTree.Qualifier> qualifiers = qualifiers();
        return declaration(start, qualifiers, typeSpec());
    }

    private GlslTree.Control ifStatement() {
        int start = at;
        int keyword = significant[at++];
        GlslTree.Range header = header();
        List<GlslTree.Statement> bodies = new ArrayList<>();
        bodies.add(statement());
        if (identifier() && text().equals("else")) {
            at++;
            bodies.add(statement());
        }
        return new GlslTree.Control(range(start), keyword, header, null, List.copyOf(bodies));
    }

    /** {@code for (init; condition; step) body}: the init may declare the loop's own names. */
    private GlslTree.Control forStatement() {
        int start = at;
        int keyword = significant[at++];
        int open = at;
        expect("(");
        GlslTree.Declaration init = startsDeclaration() ? localDeclaration() : null;
        if (init == null) {
            skipExpression(";");
            expect(";");
        }
        skipExpression(";");
        expect(";");
        skipExpression(")");
        expect(")");
        GlslTree.Range header = range(open);
        GlslTree.Statement body = statement();
        return new GlslTree.Control(range(start), keyword, header, init, List.of(body));
    }

    private GlslTree.Control whileStatement() {
        int start = at;
        int keyword = significant[at++];
        GlslTree.Range header = header();
        GlslTree.Statement body = statement();
        return new GlslTree.Control(range(start), keyword, header, null, List.of(body));
    }

    private GlslTree.Control doStatement() {
        int start = at;
        int keyword = significant[at++];
        GlslTree.Statement body = statement();
        if (!identifier() || !text().equals("while")) throw failure("expected while after a do body");
        at++;
        GlslTree.Range header = header();
        expect(";");
        return new GlslTree.Control(range(start), keyword, header, null, List.of(body));
    }

    private GlslTree.Control switchStatement() {
        int start = at;
        int keyword = significant[at++];
        GlslTree.Range header = header();
        GlslTree.Block body = block();
        return new GlslTree.Control(range(start), keyword, header, null, List.of(body));
    }

    /** {@code case value:} or {@code default:}. */
    private GlslTree.Simple label() {
        int start = at++;
        skipExpression(":");
        expect(":");
        return new GlslTree.Simple(range(start));
    }

    /** An expression, jump or empty statement, through its semicolon. */
    private GlslTree.Simple simple() {
        int start = at;
        skipExpression(";");
        expect(";");
        return new GlslTree.Simple(range(start));
    }

    private GlslTree.Range header() {
        int start = at;
        if (!symbol("(")) throw failure("expected (");
        skipBalanced();
        return range(start);
    }

    /**
     * Skips a balanced run of tokens up to, not including, one of {@code stops} outside any bracket;
     * returns its range, empty when it starts on a stop.
     */
    private GlslTree.Range skipExpression(String... stops) {
        int start = at;
        while (!atEnd() && !(isStop(stops))) {
            if (symbol("(") || symbol("[") || symbol("{")) skipBalanced();
            else if (symbol(")") || symbol("]") || symbol("}")) throw failure("unbalanced " + text());
            else at++;
        }
        if (atEnd()) throw failure("the source ends inside an expression");
        return at == start ? new GlslTree.Range(significant[start], significant[start]) : range(start);
    }

    private boolean isStop(String... stops) {
        for (String stop : stops) if (symbol(stop)) return true;
        return false;
    }

    /**
     * Skips from an opening bracket past its matching close. A semicolon never sits inside a
     * bracket here (a for header is parsed apart), so one ends an unclosed bracket where it is.
     */
    private void skipBalanced() {
        Deque<String> closers = new ArrayDeque<>();
        do {
            if (atEnd() || symbol(";")) throw failure("unclosed bracket");
            String value = text();
            switch (value) {
                case "(" -> closers.push(")");
                case "[" -> closers.push("]");
                case "{" -> closers.push("}");
                case ")", "]", "}" -> {
                    if (!value.equals(closers.peek())) throw failure("mismatched " + value);
                    closers.pop();
                }
                default -> { }
            }
            at++;
        } while (!closers.isEmpty());
    }

    private GlslTree.Range range(int startAt) {
        return new GlslTree.Range(significant[startAt], significant[at - 1] + 1);
    }

    private boolean atEnd() {
        return at >= significant.length;
    }

    private boolean identifier() {
        return !atEnd() && tokens.get(significant[at]).kind() == GlslLexer.Kind.IDENTIFIER;
    }

    private String text() {
        return tokens.get(significant[at]).text();
    }

    private boolean symbol(String value) {
        return symbolAt(at, value);
    }

    private boolean symbolAt(int position, String value) {
        return position < significant.length && tokens.get(significant[position]).symbol(value);
    }

    private int expectIdentifier() {
        if (!identifier()) throw failure("expected a name");
        return significant[at++];
    }

    private void expect(String value) {
        if (!symbol(value)) throw failure("expected " + value);
        at++;
    }

    private ParseFailure failure(String message) {
        int token = atEnd() ? tokens.size() : significant[at];
        int line = 1;
        for (int index = 0; index < token; index++) {
            line += (int) tokens.get(index).text().chars().filter(value -> value == '\n').count();
        }
        String near = atEnd() ? "the end" : "'" + tokens.get(token).text() + "'";
        return new ParseFailure(message + " at line " + line + ", near " + near);
    }
}
