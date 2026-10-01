package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The pack-authored expression language behind {@code shaders.properties} custom values.
 *
 * <p>Typed as Iris types it: every expression has a width, one for a scalar, two to four for a
 * vector, sixteen for a {@code mat4} input. Arithmetic and the math functions work component by
 * component and widen a scalar operand, as Iris's vectorized functions do; {@code vec2} through
 * {@code vec4} build vectors from scalars and smaller vectors; comparisons, logic and the
 * selection functions take scalars. Access follows Iris's accessor table: {@code 0}/{@code r}/
 * {@code x}/{@code s} through {@code 3}/{@code a}/{@code w}/{@code q} pick a vector component,
 * and on a matrix pick a column, so {@code gbufferModelViewInverse.2.1} is {@code m[2][1]}.
 *
 * <p>Evaluation runs one component (lane) at a time over the typed tree, so a vector value needs
 * no allocation per frame. The functions are OptiFine's documented custom-uniform set ({@code sin}
 * through {@code fmod}), {@code if} with any number of condition/value pairs, {@code between},
 * {@code equals}, {@code in} and {@code smooth}. An unknown function, a wrong arity, a type
 * mismatch, an unresolved symbol or a cycle is a declaration diagnostic, never a silent zero
 * handed to a running program.
 *
 * <p>{@code smooth} keeps one accumulator per call site and component, because the numeric id is
 * metadata and not identity: Complementary writes two different expressions under id 4. The first
 * value is set outright, later frames blend with the half life in deciseconds the pack asked for,
 * and the state belongs to the pack session rather than to the settings.
 */
public final class PackExpression {

    /**
     * How a compiled expression reads a named input: -1 asks for a scalar, otherwise the
     * component, with a matrix's elements numbered column-major ({@code column * 4 + row}).
     */
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

    /** The width of a named input: 1 scalar, 2-4 vector, 16 for a mat4, 0 when unknown. */
    @FunctionalInterface
    public interface Widths {
        int width(String name);
    }

    /** No constants at all, for callers that resolve none. */
    public static final Constants NO_CONSTANTS = name -> Double.NaN;

    /** The width of a {@code mat4} input. */
    public static final int MATRIX_WIDTH = 16;

    /** The half life unit the packs write: a decisecond, so two ticks. */
    private static final double HALF_LIFE_UNIT_SECONDS = 0.1;
    private static final double LN_OF_2 = Math.log(2.0);
    private static final int MAX_DEPTH = 64;
    /** Iris's accessor names, by the component they pick. */
    private static final String[] ACCESSORS = {"0rxs", "1gyt", "2bzp", "3awq"};

    private PackExpression() {}

    /** One accumulator per {@code smooth} call site and component, owned by the pack session. */
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
        private final int smoothSites;

        private Program(String source, Node root, Set<String> dependencies, int smoothSites) {
            this.source = source;
            this.root = root;
            this.dependencies = Set.copyOf(dependencies);
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

        /** Components the result has: 1 for a scalar, 2 to 4 for a vector. */
        public int width() {
            return this.root.width();
        }

        /** How much session state this program needs. */
        public int smoothSites() {
            return this.smoothSites;
        }

        /** Session state for this program's call sites. */
        public State newState() {
            return new State(this.smoothSites);
        }

        /** Folds one frame's values through, writing the scalar result. */
        public double evaluate(Inputs inputs, State state, float frameDelta) {
            return evaluate(inputs, state, frameDelta, 0);
        }

        /**
         * Folds one frame through one component of the result. A caller evaluates every lane of
         * a vector once per frame, and each lane's {@code smooth} sites advance once.
         */
        public double evaluate(Inputs inputs, State state, float frameDelta, int lane) {
            return this.root.evaluate(new Context(inputs, state, frameDelta), lane);
        }
    }

    /** Compiles one declaration that reads only host inputs, typed as the host serves them. */
    public static Program parse(String source, Constants constants) {
        return parse(source, constants, UniformRegistry::expressionWidth);
    }

    /**
     * Compiles one declaration, typing every reference by {@code widths}.
     *
     * @throws Unsupported for syntax or types the language does not cover
     */
    public static Program parse(String source, Constants constants, Widths widths) {
        String text = source == null ? "" : source;
        Parser parser = new Parser(text, constants, widths);
        Node root = parser.parse();
        return new Program(text.trim(), root, parser.dependencies(), parser.smoothSites());
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
        int width();

        /** One component; a scalar node ignores the lane, which is how it widens. */
        double evaluate(Context context, int lane);

        void collect(Set<String> dependencies);
    }

    /** One lane of an operand: a scalar answers every lane with its only value. */
    private static double lane(Node node, Context context, int lane) {
        return node.evaluate(context, node.width() == 1 ? 0 : lane);
    }

    private record Literal(double value) implements Node {
        @Override
        public int width() {
            return 1;
        }

        @Override
        public double evaluate(Context context, int lane) {
            return this.value;
        }

        @Override
        public void collect(Set<String> dependencies) {}
    }

    private record Reference(String name, int width) implements Node {
        @Override
        public double evaluate(Context context, int lane) {
            return context.inputs().value(this.name, this.width == 1 ? -1 : lane);
        }

        @Override
        public void collect(Set<String> dependencies) {
            dependencies.add(this.name);
        }
    }

    /** {@code target.index}: a vector's component, or a matrix's column. */
    private record Access(Node target, int index) implements Node {
        @Override
        public int width() {
            return this.target.width() == MATRIX_WIDTH ? 4 : 1;
        }

        @Override
        public double evaluate(Context context, int lane) {
            return this.target.width() == MATRIX_WIDTH
                    ? this.target.evaluate(context, this.index * 4 + lane)
                    : this.target.evaluate(context, this.index);
        }

        @Override
        public void collect(Set<String> dependencies) {
            this.target.collect(dependencies);
        }
    }

    /** {@code vecN(...)}: lane by lane from the arguments in order, or one scalar widened. */
    private record Construct(int width, List<Node> parts) implements Node {
        @Override
        public double evaluate(Context context, int lane) {
            if (this.parts.size() == 1 && this.parts.get(0).width() == 1) {
                return this.parts.get(0).evaluate(context, 0);
            }
            int remaining = lane;
            for (Node part : this.parts) {
                if (remaining < part.width()) {
                    return part.evaluate(context, remaining);
                }
                remaining -= part.width();
            }
            throw new IllegalStateException("lane " + lane + " outside vec" + this.width);
        }

        @Override
        public void collect(Set<String> dependencies) {
            for (Node part : this.parts) {
                part.collect(dependencies);
            }
        }
    }

    private record Negate(Node value) implements Node {
        @Override
        public int width() {
            return this.value.width();
        }

        @Override
        public double evaluate(Context context, int lane) {
            return -this.value.evaluate(context, lane);
        }

        @Override
        public void collect(Set<String> dependencies) {
            this.value.collect(dependencies);
        }
    }

    private record Not(Node value) implements Node {
        @Override
        public int width() {
            return 1;
        }

        @Override
        public double evaluate(Context context, int lane) {
            return this.value.evaluate(context, 0) == 0.0 ? 1.0 : 0.0;
        }

        @Override
        public void collect(Set<String> dependencies) {
            this.value.collect(dependencies);
        }
    }

    private record Binary(String operator, Node left, Node right, int width) implements Node {
        @Override
        public double evaluate(Context context, int lane) {
            double a = lane(this.left, context, lane);
            double b = lane(this.right, context, lane);
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
    private record Choose(Node condition, Node whenTrue, Node whenFalse, int width) implements Node {
        @Override
        public double evaluate(Context context, int lane) {
            return lane(this.condition.evaluate(context, 0) != 0.0 ? this.whenTrue : this.whenFalse,
                    context, lane);
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
     * not advance; the rest fold every argument, component by component.
     */
    private record Call(Function function, List<Node> arguments, int smoothSlot, int width)
            implements Node {
        @Override
        public double evaluate(Context context, int lane) {
            if (this.function == Function.IF) {
                // if(c1, v1, c2, v2, ..., else): the first true condition's value.
                int last = this.arguments.size() - 1;
                for (int index = 0; index < last; index += 2) {
                    if (this.arguments.get(index).evaluate(context, 0) != 0.0) {
                        return lane(this.arguments.get(index + 1), context, lane);
                    }
                }
                return lane(this.arguments.get(last), context, lane);
            }
            if (this.function == Function.RANDOM) {
                return java.util.concurrent.ThreadLocalRandom.current().nextDouble();
            }
            if (this.function == Function.IN) {
                double needle = this.arguments.get(0).evaluate(context, 0);
                for (int index = 1; index < this.arguments.size(); index++) {
                    if (this.arguments.get(index).evaluate(context, 0) == needle) {
                        return 1.0;
                    }
                }
                return 0.0;
            }
            if (this.function == Function.SMOOTH) {
                int first = this.arguments.size() - 3;
                double value = lane(this.arguments.get(first), context, lane);
                float rise = (float) this.arguments.get(first + 1).evaluate(context, 0);
                float fall = (float) this.arguments.get(first + 2).evaluate(context, 0);
                return smooth(context, this.smoothSlot + lane, value, rise, fall);
            }
            double first = lane(this.arguments.get(0), context, lane);
            return switch (this.function) {
                case ABS -> Math.abs(first);
                case SIN -> Math.sin(first);
                case COS -> Math.cos(first);
                case ASIN -> Math.asin(first);
                case ACOS -> Math.acos(first);
                case TAN -> Math.tan(first);
                case ATAN -> this.arguments.size() == 2
                        ? Math.atan2(first, lane(this.arguments.get(1), context, lane))
                        : Math.atan(first);
                case TORAD -> Math.toRadians(first);
                case TODEG -> Math.toDegrees(first);
                case FLOOR -> Math.floor(first);
                case CEIL -> Math.ceil(first);
                case FRAC -> first - Math.floor(first);
                case ROUND -> (double) Math.round(first);
                case SIGNUM -> Math.signum(first);
                case EXP -> Math.exp(first);
                case LOG -> Math.log(first);
                case POW -> Math.pow(first, lane(this.arguments.get(1), context, lane));
                case SQRT -> Math.sqrt(first);
                case FMOD -> {
                    // OptiFine's fmod is a floor modulo: the result takes the divisor's sign.
                    double divisor = lane(this.arguments.get(1), context, lane);
                    yield divisor == 0.0 ? 0.0 : first - divisor * Math.floor(first / divisor);
                }
                case BETWEEN -> first >= this.arguments.get(1).evaluate(context, 0)
                        && first <= this.arguments.get(2).evaluate(context, 0) ? 1.0 : 0.0;
                case EQUALS -> Math.abs(first - this.arguments.get(1).evaluate(context, 0))
                        <= this.arguments.get(2).evaluate(context, 0) ? 1.0 : 0.0;
                case MAX -> {
                    double result = first;
                    for (int index = 1; index < this.arguments.size(); index++) {
                        result = Math.max(result, lane(this.arguments.get(index), context, lane));
                    }
                    yield result;
                }
                case MIN -> {
                    double result = first;
                    for (int index = 1; index < this.arguments.size(); index++) {
                        result = Math.min(result, lane(this.arguments.get(index), context, lane));
                    }
                    yield result;
                }
                case CLAMP -> {
                    double low = lane(this.arguments.get(1), context, lane);
                    double high = lane(this.arguments.get(2), context, lane);
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

    /** How a function types its arguments. */
    private enum Shape {
        /** Every argument component-wise, a scalar widening to the others' width. */
        LANES,
        /** Scalars only. */
        SCALAR,
        /** {@code if}: scalar conditions, values of one width. */
        SELECT,
        /** {@code smooth}: a component-wise value, scalar id and half lives. */
        SMOOTH,
        /** {@code vecN}: components in order, or one scalar widened. */
        CONSTRUCT
    }

    /** The functions the packs reach for, with the arity each one accepts. */
    private enum Function {
        ABS(1, 1, Shape.LANES),
        SIN(1, 1, Shape.LANES),
        COS(1, 1, Shape.LANES),
        ASIN(1, 1, Shape.LANES),
        ACOS(1, 1, Shape.LANES),
        TAN(1, 1, Shape.LANES),
        ATAN(1, 2, Shape.LANES),
        TORAD(1, 1, Shape.LANES),
        TODEG(1, 1, Shape.LANES),
        FLOOR(1, 1, Shape.LANES),
        CEIL(1, 1, Shape.LANES),
        FRAC(1, 1, Shape.LANES),
        ROUND(1, 1, Shape.LANES),
        SIGNUM(1, 1, Shape.LANES),
        EXP(1, 1, Shape.LANES),
        LOG(1, 1, Shape.LANES),
        POW(2, 2, Shape.LANES),
        SQRT(1, 1, Shape.LANES),
        FMOD(2, 2, Shape.LANES),
        RANDOM(0, 0, Shape.SCALAR),
        MAX(2, Integer.MAX_VALUE, Shape.LANES),
        MIN(2, Integer.MAX_VALUE, Shape.LANES),
        CLAMP(3, 3, Shape.LANES),
        BETWEEN(3, 3, Shape.SCALAR),
        EQUALS(3, 3, Shape.SCALAR),
        IF(3, Integer.MAX_VALUE, Shape.SELECT),
        IN(2, Integer.MAX_VALUE, Shape.SCALAR),
        SMOOTH(3, 4, Shape.SMOOTH),
        VEC2(1, 4, Shape.CONSTRUCT),
        VEC3(1, 4, Shape.CONSTRUCT),
        VEC4(1, 4, Shape.CONSTRUCT);

        private final int minimum;
        private final int maximum;
        private final Shape shape;

        Function(int minimum, int maximum, Shape shape) {
            this.minimum = minimum;
            this.maximum = maximum;
            this.shape = shape;
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
        private final Widths widths;
        private final Set<String> dependencies = new TreeSet<>();
        private final int[] smoothSites = new int[1];
        private int index;
        private int depth;

        private Parser(String source, Constants constants, Widths widths) {
            this.source = source;
            this.constants = constants == null ? NO_CONSTANTS : constants;
            this.widths = widths == null ? UniformRegistry::expressionWidth : widths;
        }

        private Node parse() {
            Node result = conditional();
            skip();
            if (this.index != this.source.length()) {
                throw new Unsupported("CUSTOM_EXPRESSION_UNSUPPORTED",
                        this.source.substring(this.index));
            }
            if (result.width() == MATRIX_WIDTH) {
                throw new Unsupported("CUSTOM_EXPRESSION_TYPE", "mat4 result");
            }
            result.collect(this.dependencies);
            return result;
        }

        private Set<String> dependencies() {
            return this.dependencies;
        }

        private int smoothSites() {
            return this.smoothSites[0];
        }

        private Node conditional() {
            Node result = or();
            if (take("?")) {
                Node whenTrue = conditional();
                require(":");
                Node whenFalse = conditional();
                scalar(result, "?");
                result = new Choose(result, whenTrue, whenFalse, unify("?:", whenTrue, whenFalse));
            }
            return result;
        }

        private Node or() {
            Node result = and();
            while (take("||")) {
                result = scalarBinary("||", result, and());
            }
            return result;
        }

        private Node and() {
            Node result = equality();
            while (take("&&")) {
                result = scalarBinary("&&", result, equality());
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
                result = scalarBinary(operator, result, relation());
            }
        }

        private Node relation() {
            Node result = add();
            while (true) {
                String operator = operator(">=", "<=", ">", "<");
                if (operator == null) {
                    return result;
                }
                result = scalarBinary(operator, result, add());
            }
        }

        private Node add() {
            Node result = multiply();
            while (true) {
                String operator = operator("+", "-");
                if (operator == null) {
                    return result;
                }
                Node right = multiply();
                result = new Binary(operator, result, right, unify(operator, result, right));
            }
        }

        private Node multiply() {
            Node result = unary();
            while (true) {
                String operator = operator("*", "/", "%");
                if (operator == null) {
                    return result;
                }
                Node right = unary();
                result = new Binary(operator, result, right, unify(operator, result, right));
            }
        }

        private Node scalarBinary(String operator, Node left, Node right) {
            scalar(left, operator);
            scalar(right, operator);
            return new Binary(operator, left, right, 1);
        }

        private Node unary() {
            enter();
            try {
                if (take("!")) {
                    Node value = unary();
                    scalar(value, "!");
                    return new Not(value);
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
                    return accesses(result);
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
                    return accesses(call(name));
                }
                return reference(name);
            } finally {
                this.depth--;
            }
        }

        /** A bare name: a constant, or an input typed by its width, with any accessors. */
        private Node reference(String name) {
            int width = this.widths.width(name);
            if (!peekAccess()) {
                double constant = this.constants.value(name);
                if (!Double.isNaN(constant)) {
                    return new Literal(constant);
                }
            }
            // An unknown name stays a scalar here; settings reports it unresolved. Accessing
            // one is allowed so that report names the missing input, not the accessor.
            if (width <= 0) {
                width = peekAccess() ? 4 : 1;
            }
            return accesses(new Reference(name, width));
        }

        /** Applies every trailing {@code .accessor}. */
        private Node accesses(Node target) {
            Node result = target;
            while (peekAccess()) {
                skip();
                this.index++;
                int start = this.index;
                int component = accessor();
                int limit = result.width() == MATRIX_WIDTH ? 4 : result.width();
                if (result.width() == 1 || component >= limit) {
                    String accessor = this.source.substring(start, this.index);
                    throw new Unsupported("CUSTOM_EXPRESSION_COMPONENT", result instanceof Reference reference
                            ? reference.name() + "." + accessor
                            : "." + accessor + " of width " + result.width());
                }
                result = new Access(result, component);
            }
            return result;
        }

        private boolean peekAccess() {
            int mark = this.index;
            skip();
            boolean access = this.index + 1 < this.source.length()
                    && this.source.charAt(this.index) == '.'
                    && !Character.isWhitespace(this.source.charAt(this.index + 1));
            this.index = mark;
            return access;
        }

        /** One accessor name from Iris's table; a swizzle or anything longer is refused. */
        private int accessor() {
            int start = this.index;
            while (this.index < this.source.length()
                    && (Character.isLetterOrDigit(this.source.charAt(this.index))
                    || this.source.charAt(this.index) == '_')) {
                this.index++;
            }
            String name = this.source.substring(start, this.index);
            if (name.length() == 1) {
                for (int component = 0; component < ACCESSORS.length; component++) {
                    if (ACCESSORS[component].indexOf(name.charAt(0)) >= 0) {
                        return component;
                    }
                }
            }
            throw new Unsupported("CUSTOM_EXPRESSION_COMPONENT", "." + name);
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
            if (arguments.size() < function.minimum || arguments.size() > function.maximum
                    || (function == Function.IF && arguments.size() % 2 == 0)) {
                throw new Unsupported("CUSTOM_EXPRESSION_ARITY", name);
            }
            int width = width(function, name, arguments);
            int slot = -1;
            if (function == Function.SMOOTH) {
                slot = this.smoothSites[0];
                this.smoothSites[0] += width;
            }
            if (function.shape == Shape.CONSTRUCT) {
                return new Construct(width, List.copyOf(arguments));
            }
            return new Call(function, List.copyOf(arguments), slot, width);
        }

        /** Types one call by its shape, or refuses it. */
        private int width(Function function, String name, List<Node> arguments) {
            return switch (function.shape) {
                case SCALAR -> {
                    for (Node argument : arguments) {
                        scalar(argument, name);
                    }
                    yield 1;
                }
                case LANES -> {
                    int width = 1;
                    for (Node argument : arguments) {
                        width = unify(name, width, argument.width());
                    }
                    yield width;
                }
                case SELECT -> {
                    int last = arguments.size() - 1;
                    int width = arguments.get(last).width();
                    for (int index = 0; index < last; index += 2) {
                        scalar(arguments.get(index), name);
                        width = unify(name, width, arguments.get(index + 1).width());
                    }
                    yield width;
                }
                case SMOOTH -> {
                    int first = arguments.size() - 3;
                    for (int index = 0; index < arguments.size(); index++) {
                        if (index != first) {
                            scalar(arguments.get(index), name);
                        }
                    }
                    int width = arguments.get(first).width();
                    if (width == MATRIX_WIDTH) {
                        throw new Unsupported("CUSTOM_EXPRESSION_TYPE", name + " of mat4");
                    }
                    yield width;
                }
                case CONSTRUCT -> {
                    int width = function.ordinal() - Function.VEC2.ordinal() + 2;
                    int total = 0;
                    for (Node argument : arguments) {
                        if (argument.width() == MATRIX_WIDTH) {
                            throw new Unsupported("CUSTOM_EXPRESSION_TYPE", name + " of mat4");
                        }
                        total += argument.width();
                    }
                    boolean widened = arguments.size() == 1 && arguments.get(0).width() == 1;
                    if (!widened && total != width) {
                        throw new Unsupported("CUSTOM_EXPRESSION_TYPE",
                                name + " given " + total + " components");
                    }
                    yield width;
                }
            };
        }

        private static void scalar(Node node, String operator) {
            if (node.width() != 1) {
                throw new Unsupported("CUSTOM_EXPRESSION_TYPE",
                        operator + " needs a scalar, got width " + node.width());
            }
        }

        private static int unify(String operator, Node left, Node right) {
            return unify(operator, left.width(), right.width());
        }

        /** Equal widths, or a scalar beside anything; a matrix only through its accessors. */
        private static int unify(String operator, int left, int right) {
            if (left == MATRIX_WIDTH || right == MATRIX_WIDTH) {
                throw new Unsupported("CUSTOM_EXPRESSION_TYPE", operator + " of mat4");
            }
            if (left == right || right == 1) {
                return left;
            }
            if (left == 1) {
                return right;
            }
            throw new Unsupported("CUSTOM_EXPRESSION_TYPE",
                    operator + " of widths " + left + " and " + right);
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
