package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The pack-authored expression language behind {@code shaders.properties} custom values.
 *
 * <p>Scoped to what the pinned packs write and no wider: scalar arithmetic and comparisons,
 * vector components such as {@code eyeBrightness.y}, the named biome constants, {@code if},
 * {@code in}, {@code smooth}, and the math those expressions reach for. An unknown function, a
 * wrong arity, an unresolved symbol or a cycle is a declaration diagnostic, never a silent zero
 * handed to a running program.
 *
 * <p>{@code smooth} keeps one accumulator per call site, because the numeric id is metadata and
 * not identity: Complementary writes two different expressions under id 4. The first value is set
 * outright, later frames blend with the half life in deciseconds the pack asked for, and the state
 * belongs to the pack session rather than to the settings.
 */
public final class PackExpression {

    /** How a compiled expression reads a named input; -1 asks for the scalar itself. */
    @FunctionalInterface
    public interface Inputs {
        double value(String name, int component);
    }

    /** Named constants, such as the {@code BIOME_*} symbols. */
    @FunctionalInterface
    public interface Constants {
        /** The constant's value, or NaN when the name is not a constant. */
        double value(String name);
    }

    /** No constants at all, for callers that resolve none. */
    public static final Constants NO_CONSTANTS = name -> Double.NaN;

    /** The half life unit the packs write: a decisecond, so two ticks. */
    private static final double HALF_LIFE_UNIT_SECONDS = 0.1;
    private static final double LN_OF_2 = Math.log(2.0);
    private static final int MAX_DEPTH = 64;

    private PackExpression() {}

    /** One accumulator per {@code smooth} call site, owned by the pack session. */
    public static final class State {
        private final float[] accumulators;
        private final boolean[] started;

        private State(int smoothSites) {
            this.accumulators = new float[smoothSites];
            this.started = new boolean[smoothSites];
        }

        /** Drops every accumulator, as a pack reload must. */
        public void reset() {
            java.util.Arrays.fill(this.accumulators, 0.0F);
            java.util.Arrays.fill(this.started, false);
        }
    }

    /** A compiled expression, which is what a declaration holds. */
    public static final class Program {
        private final String source;
        private final Node root;
        private final Set<String> dependencies;
        private final Set<String> componentReferences;
        private final int smoothSites;

        private Program(String source, Node root, Set<String> dependencies,
                        Set<String> componentReferences, int smoothSites) {
            this.source = source;
            this.root = root;
            this.dependencies = Set.copyOf(dependencies);
            this.componentReferences = Set.copyOf(componentReferences);
            this.smoothSites = smoothSites;
        }

        /** The expression this was compiled from, for a diagnostic that names it. */
        public String source() {
            return this.source;
        }

        /** Every name this program reads, in sorted order. */
        public Set<String> dependencies() {
            return this.dependencies;
        }

        /** Every {@code name.component} this program reads, for the type check. */
        public Set<String> componentReferences() {
            return this.componentReferences;
        }

        /** How much session state this program needs. */
        public int smoothSites() {
            return this.smoothSites;
        }

        /** Session state for this program's call sites. */
        public State newState() {
            return new State(this.smoothSites);
        }

        /** Folds one frame's values through, writing the result. */
        public double evaluate(Inputs inputs, State state, float frameDelta) {
            return this.root.evaluate(new Context(inputs, state, frameDelta));
        }
    }

    /**
     * Compiles one declaration.
     *
     * @throws Unsupported for syntax the language does not cover
     */
    public static Program parse(String source, Constants constants) {
        String text = source == null ? "" : source;
        Parser parser = new Parser(text, constants);
        Node root = parser.parse();
        return new Program(text.trim(), root, parser.dependencies(), parser.componentReferences(),
                parser.smoothSites());
    }

    /** A declaration the language cannot compile, with the code the report carries. */
    public static final class Unsupported extends RuntimeException {
        private final String code;
        private final String detail;

        private Unsupported(String code, String detail) {
            super(code + (detail == null || detail.isBlank() ? "" : ":" + detail));
            this.code = code;
            this.detail = detail == null ? "" : detail;
        }

        public String code() {
            return this.code;
        }

        public String detail() {
            return this.detail;
        }
    }

    private record Context(Inputs inputs, State state, float frameDelta) {}

    private interface Node {
        double evaluate(Context context);

        void collect(Set<String> dependencies);
    }

    private record Literal(double value) implements Node {
        @Override
        public double evaluate(Context context) {
            return this.value;
        }

        @Override
        public void collect(Set<String> dependencies) {}
    }

    private record Reference(String name, int component) implements Node {
        @Override
        public double evaluate(Context context) {
            return context.inputs().value(this.name, this.component);
        }

        @Override
        public void collect(Set<String> dependencies) {
            dependencies.add(this.name);
        }
    }

    private record Negate(Node value) implements Node {
        @Override
        public double evaluate(Context context) {
            return -this.value.evaluate(context);
        }

        @Override
        public void collect(Set<String> dependencies) {
            this.value.collect(dependencies);
        }
    }

    private record Not(Node value) implements Node {
        @Override
        public double evaluate(Context context) {
            return this.value.evaluate(context) == 0.0 ? 1.0 : 0.0;
        }

        @Override
        public void collect(Set<String> dependencies) {
            this.value.collect(dependencies);
        }
    }

    private record Binary(String operator, Node left, Node right) implements Node {
        @Override
        public double evaluate(Context context) {
            double a = this.left.evaluate(context);
            double b = this.right.evaluate(context);
            return switch (this.operator) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0.0 ? 0.0 : a / b;
                case "%" -> b == 0.0 ? 0.0 : a % b;
                case "==" -> a == b ? 1.0 : 0.0;
                case "!=" -> a != b ? 1.0 : 0.0;
                case ">" -> a > b ? 1.0 : 0.0;
                case ">=" -> a >= b ? 1.0 : 0.0;
                case "<" -> a < b ? 1.0 : 0.0;
                case "<=" -> a <= b ? 1.0 : 0.0;
                case "&&" -> a != 0.0 && b != 0.0 ? 1.0 : 0.0;
                case "||" -> a != 0.0 || b != 0.0 ? 1.0 : 0.0;
                default -> throw new IllegalStateException("unknown operator " + this.operator);
            };
        }

        @Override
        public void collect(Set<String> dependencies) {
            this.left.collect(dependencies);
            this.right.collect(dependencies);
        }
    }

    /** {@code condition ? whenTrue : whenFalse}, with both branches parsed. */
    private record Choose(Node condition, Node whenTrue, Node whenFalse) implements Node {
        @Override
        public double evaluate(Context context) {
            return (this.condition.evaluate(context) != 0.0 ? this.whenTrue : this.whenFalse)
                    .evaluate(context);
        }

        @Override
        public void collect(Set<String> dependencies) {
            this.condition.collect(dependencies);
            this.whenTrue.collect(dependencies);
            this.whenFalse.collect(dependencies);
        }
    }

    /**
     * A call. {@code if} evaluates one branch, because a {@code smooth} in the other branch must
     * not advance; the rest fold every argument.
     */
    private record Call(Function function, List<Node> arguments, int smoothSlot) implements Node {
        @Override
        public double evaluate(Context context) {
            if (this.function == Function.IF) {
                return (this.arguments.get(0).evaluate(context) != 0.0
                        ? this.arguments.get(1) : this.arguments.get(2)).evaluate(context);
            }
            if (this.function == Function.IN) {
                double needle = this.arguments.get(0).evaluate(context);
                for (int index = 1; index < this.arguments.size(); index++) {
                    if (this.arguments.get(index).evaluate(context) == needle) {
                        return 1.0;
                    }
                }
                return 0.0;
            }
            if (this.function == Function.SMOOTH) {
                int first = this.arguments.size() - 3;
                double value = this.arguments.get(first).evaluate(context);
                float rise = (float) this.arguments.get(first + 1).evaluate(context);
                float fall = (float) this.arguments.get(first + 2).evaluate(context);
                return smooth(context, this.smoothSlot, value, rise, fall);
            }
            double first = this.arguments.get(0).evaluate(context);
            return switch (this.function) {
                case ABS -> Math.abs(first);
                case SIN -> Math.sin(first);
                case SQRT -> Math.sqrt(first);
                case MAX -> {
                    double result = first;
                    for (int index = 1; index < this.arguments.size(); index++) {
                        result = Math.max(result, this.arguments.get(index).evaluate(context));
                    }
                    yield result;
                }
                case MIN -> {
                    double result = first;
                    for (int index = 1; index < this.arguments.size(); index++) {
                        result = Math.min(result, this.arguments.get(index).evaluate(context));
                    }
                    yield result;
                }
                case CLAMP -> {
                    double low = this.arguments.get(1).evaluate(context);
                    double high = this.arguments.get(2).evaluate(context);
                    yield Math.min(Math.max(first, low), high);
                }
                default -> throw new IllegalStateException("unfolded function " + this.function);
            };
        }

        @Override
        public void collect(Set<String> dependencies) {
            for (Node argument : this.arguments) {
                argument.collect(dependencies);
            }
        }
    }

    /**
     * One call site's accumulator. The first value is set outright, a half life of zero gives no
     * smoothing, and a frame with no duration holds the accumulator where it stands.
     */
    private static double smooth(Context context, int slot, double value, float rise, float fall) {
        State state = context.state();
        if (slot < 0 || slot >= state.accumulators.length) {
            return value;
        }
        if (!state.started[slot]) {
            state.started[slot] = true;
            state.accumulators[slot] = (float) value;
            return state.accumulators[slot];
        }
        float dt = context.frameDelta();
        if (!(dt > 0.0F)) {
            return state.accumulators[slot];
        }
        float halfLife = value > state.accumulators[slot] ? rise : fall;
        float factor = 1.0F - (float) Math.exp(-decay(halfLife) * dt);
        state.accumulators[slot] = state.accumulators[slot]
                + (float) (value - state.accumulators[slot]) * factor;
        return state.accumulators[slot];
    }

    private static double decay(float halfLife) {
        return halfLife <= 0.0F ? Double.POSITIVE_INFINITY
                : 1.0 / (halfLife * HALF_LIFE_UNIT_SECONDS / LN_OF_2);
    }

    /** The functions the pinned packs reach for, with the arity each one accepts. */
    private enum Function {
        ABS(1, 1),
        SIN(1, 1),
        SQRT(1, 1),
        MAX(2, Integer.MAX_VALUE),
        MIN(2, Integer.MAX_VALUE),
        CLAMP(3, 3),
        IF(3, 3),
        IN(2, Integer.MAX_VALUE),
        SMOOTH(3, 4);

        private final int minimum;
        private final int maximum;

        Function(int minimum, int maximum) {
            this.minimum = minimum;
            this.maximum = maximum;
        }

        private static Function named(String name) {
            for (Function function : values()) {
                if (function.name().equalsIgnoreCase(name)) {
                    return function;
                }
            }
            return null;
        }
    }

    private static final class Parser {
        private final String source;
        private final Constants constants;
        private final Set<String> dependencies = new TreeSet<>();
        private final Set<String> componentReferences = new TreeSet<>();
        private final int[] smoothSites = new int[1];
        private int index;
        private int depth;

        private Parser(String source, Constants constants) {
            this.source = source;
            this.constants = constants == null ? NO_CONSTANTS : constants;
        }

        private Node parse() {
            Node result = conditional();
            skip();
            if (this.index != this.source.length()) {
                throw new Unsupported("CUSTOM_EXPRESSION_UNSUPPORTED",
                        this.source.substring(this.index));
            }
            result.collect(this.dependencies);
            return result;
        }

        private Set<String> dependencies() {
            return this.dependencies;
        }

        private Set<String> componentReferences() {
            return this.componentReferences;
        }

        private int smoothSites() {
            return this.smoothSites[0];
        }

        private Node conditional() {
            Node result = or();
            if (take("?")) {
                Node whenTrue = conditional();
                require(":");
                result = new Choose(result, whenTrue, conditional());
            }
            return result;
        }

        private Node or() {
            Node result = and();
            while (take("||")) {
                result = new Binary("||", result, and());
            }
            return result;
        }

        private Node and() {
            Node result = equality();
            while (take("&&")) {
                result = new Binary("&&", result, equality());
            }
            return result;
        }

        private Node equality() {
            Node result = relation();
            while (true) {
                String operator = operator("==", "!=");
                if (operator == null) {
                    return result;
                }
                result = new Binary(operator, result, relation());
            }
        }

        private Node relation() {
            Node result = add();
            while (true) {
                String operator = operator(">=", "<=", ">", "<");
                if (operator == null) {
                    return result;
                }
                result = new Binary(operator, result, add());
            }
        }

        private Node add() {
            Node result = multiply();
            while (true) {
                String operator = operator("+", "-");
                if (operator == null) {
                    return result;
                }
                result = new Binary(operator, result, multiply());
            }
        }

        private Node multiply() {
            Node result = unary();
            while (true) {
                String operator = operator("*", "/", "%");
                if (operator == null) {
                    return result;
                }
                result = new Binary(operator, result, unary());
            }
        }

        private Node unary() {
            enter();
            try {
                if (take("!")) {
                    return new Not(unary());
                }
                if (take("-")) {
                    return new Negate(unary());
                }
                if (take("+")) {
                    return unary();
                }
                if (take("(")) {
                    Node result = conditional();
                    require(")");
                    return result;
                }
                if (skipWord("true")) {
                    return new Literal(1.0);
                }
                if (skipWord("false")) {
                    return new Literal(0.0);
                }
                if (this.index < this.source.length()
                        && (Character.isDigit(this.source.charAt(this.index))
                        || this.source.charAt(this.index) == '.')) {
                    return new Literal(number());
                }
                String name = identifier();
                if (peek("(")) {
                    return call(name);
                }
                return reference(name);
            } finally {
                this.depth--;
            }
        }

        /** A bare name: a vector component, a constant, or something to resolve later. */
        private Node reference(String name) {
            int component = component();
            if (component >= 0) {
                this.componentReferences.add(name + "." + "xyzw".charAt(component));
            }
            double constant = this.constants.value(name);
            if (!Double.isNaN(constant) && component < 0) {
                return new Literal(constant);
            }
            return new Reference(name, component);
        }

        private Node call(String name) {
            Function function = Function.named(name);
            if (function == null) {
                throw new Unsupported("CUSTOM_EXPRESSION_UNKNOWN_FUNCTION", name);
            }
            require("(");
            List<Node> arguments = new ArrayList<>();
            if (!peek(")")) {
                do {
                    arguments.add(conditional());
                } while (take(","));
            }
            require(")");
            if (arguments.size() < function.minimum || arguments.size() > function.maximum) {
                throw new Unsupported("CUSTOM_EXPRESSION_ARITY", name);
            }
            int slot = -1;
            if (function == Function.SMOOTH) {
                slot = this.smoothSites[0]++;
            }
            return new Call(function, List.copyOf(arguments), slot);
        }

        /** Reads {@code .x}/{@code .y}/{@code .z}/{@code .w}, or -1 for a bare name. */
        private int component() {
            int mark = this.index;
            skip();
            if (this.index >= this.source.length() || this.source.charAt(this.index) != '.') {
                this.index = mark;
                return -1;
            }
            if (this.index + 1 >= this.source.length()) {
                throw new Unsupported("CUSTOM_EXPRESSION_UNSUPPORTED", ".");
            }
            int component = "xyzw".indexOf(Character.toLowerCase(this.source.charAt(this.index + 1)));
            if (component < 0) {
                throw new Unsupported("CUSTOM_EXPRESSION_COMPONENT", "."
                        + this.source.charAt(this.index + 1));
            }
            int end = this.index + 2;
            if (end < this.source.length()
                    && (Character.isLetterOrDigit(this.source.charAt(end))
                    || this.source.charAt(end) == '_')) {
                throw new Unsupported("CUSTOM_EXPRESSION_COMPONENT",
                        this.source.substring(this.index, end + 1));
            }
            this.index = end;
            return component;
        }

        private double number() {
            int start = this.index;
            while (this.index < this.source.length()
                    && (Character.isDigit(this.source.charAt(this.index))
                    || ".eEfF+-".indexOf(this.source.charAt(this.index)) >= 0)) {
                char current = this.source.charAt(this.index);
                if ((current == '+' || current == '-') && this.index > start
                        && this.source.charAt(this.index - 1) != 'e'
                        && this.source.charAt(this.index - 1) != 'E') {
                    break;
                }
                this.index++;
            }
            try {
                return Double.parseDouble(this.source.substring(start, this.index)
                        .replace("f", "").replace("F", ""));
            } catch (NumberFormatException failure) {
                throw new Unsupported("CUSTOM_EXPRESSION_UNSUPPORTED",
                        this.source.substring(start, this.index));
            }
        }

        private String identifier() {
            skip();
            int start = this.index;
            if (this.index >= this.source.length()
                    || !(Character.isLetter(this.source.charAt(this.index))
                    || this.source.charAt(this.index) == '_')) {
                throw new Unsupported("CUSTOM_EXPRESSION_UNSUPPORTED", "identifier");
            }
            this.index++;
            while (this.index < this.source.length()
                    && (Character.isLetterOrDigit(this.source.charAt(this.index))
                    || this.source.charAt(this.index) == '_')) {
                this.index++;
            }
            return this.source.substring(start, this.index);
        }

        private String operator(String... operators) {
            for (String operator : operators) {
                if (take(operator)) {
                    return operator;
                }
            }
            return null;
        }

        private boolean take(String token) {
            skip();
            if (this.source.startsWith(token, this.index)) {
                this.index += token.length();
                return true;
            }
            return false;
        }

        private boolean peek(String token) {
            skip();
            return this.source.startsWith(token, this.index);
        }

        private boolean skipWord(String word) {
            skip();
            if (!this.source.startsWith(word, this.index)) {
                return false;
            }
            int end = this.index + word.length();
            if (end < this.source.length()
                    && (Character.isLetterOrDigit(this.source.charAt(end))
                    || this.source.charAt(end) == '_')) {
                return false;
            }
            this.index = end;
            return true;
        }

        private void require(String token) {
            if (!take(token)) {
                throw new Unsupported("CUSTOM_EXPRESSION_UNSUPPORTED", token);
            }
        }

        private void enter() {
            if (++this.depth > MAX_DEPTH) {
                throw new Unsupported("CUSTOM_EXPRESSION_TOO_DEEP", "");
            }
        }

        private void skip() {
            while (this.index < this.source.length()
                    && Character.isWhitespace(this.source.charAt(this.index))) {
                this.index++;
            }
        }
    }
}
