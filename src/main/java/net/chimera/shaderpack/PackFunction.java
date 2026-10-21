package net.chimera.shaderpack;

import java.util.function.DoubleBinaryOperator;
import java.util.function.DoubleUnaryOperator;

/**
 * The functions a {@link PackExpression} may call: Iris's set (IrisFunctions), with the arity and
 * argument shape each accepts. A math function carries its operation on one argument, on two, or
 * both; the rest are folded by the call that uses them. Iris has no {@code round}: its entry is
 * commented out, so an expression calling it is refused as Iris refuses it.
 */
enum PackFunction {
    ABS(1, 1, Shape.LANES, Math::abs),
    NEGATE(1, 1, Shape.LANES, value -> -value),
    SIN(1, 1, Shape.LANES, Math::sin),
    COS(1, 1, Shape.LANES, Math::cos),
    ASIN(1, 1, Shape.LANES, Math::asin),
    ACOS(1, 1, Shape.LANES, Math::acos),
    TAN(1, 1, Shape.LANES, Math::tan),
    /** One argument is {@code atan}; two are {@code atan2}. */
    ATAN(1, 2, Shape.LANES, Math::atan, Math::atan2),
    ATAN2(2, 2, Shape.LANES, null, Math::atan2),
    TORAD(1, 1, Shape.LANES, Math::toRadians),
    TODEG(1, 1, Shape.LANES, Math::toDegrees),
    RADIANS(1, 1, Shape.LANES, Math::toRadians),
    DEGREES(1, 1, Shape.LANES, Math::toDegrees),
    FLOOR(1, 1, Shape.LANES, Math::floor),
    CEIL(1, 1, Shape.LANES, Math::ceil),
    FRAC(1, 1, Shape.LANES, value -> value - Math.floor(value)),
    SIGNUM(1, 1, Shape.LANES, Math::signum),
    SIGN(1, 1, Shape.LANES, Math::signum),
    EXP(1, 1, Shape.LANES, Math::exp),
    EXP2(1, 1, Shape.LANES, value -> Math.pow(2.0, value)),
    EXP10(1, 1, Shape.LANES, value -> Math.pow(10.0, value)),
    /** One argument is the natural logarithm; two are {@code log(base, value)}. */
    LOG(1, 2, Shape.LANES, Math::log, (base, value) -> Math.log(value) / Math.log(base)),
    LOG2(1, 1, Shape.LANES, value -> Math.log(value) / Math.log(2.0)),
    LOG10(1, 1, Shape.LANES, Math::log10),
    POW(2, 2, Shape.LANES, null, Math::pow),
    SQRT(1, 1, Shape.LANES, Math::sqrt),
    FMOD(2, 2, Shape.LANES, null),
    ADD(2, 2, Shape.LANES, null, Double::sum),
    SUBTRACT(2, 2, Shape.LANES, null, (a, b) -> a - b),
    MULTIPLY(2, 2, Shape.LANES, null, (a, b) -> a * b),
    DIVIDE(2, 2, Shape.LANES, null, (a, b) -> a / b),
    REMAINDER(2, 2, Shape.LANES, null, (a, b) -> a % b),
    MIX(3, 3, Shape.LANES, null),
    /** {@code edge(edge, x)}: zero below the edge, one from it on. */
    EDGE(2, 2, Shape.LANES, null, (edge, x) -> x < edge ? 0.0 : 1.0),
    /** {@code random()} in [0, 1), or {@code random(min, max)}. */
    RANDOM(0, 2, Shape.SCALAR, null),
    /**
     * Iris's three-or-more-argument {@code max} and {@code min} read the second argument again for
     * each further one, so only the first two count.
     */
    MAX(2, Integer.MAX_VALUE, Shape.LANES, null, Math::max),
    MIN(2, Integer.MAX_VALUE, Shape.LANES, null, Math::min),
    CLAMP(3, 3, Shape.LANES, null),
    BETWEEN(3, 3, Shape.SCALAR, null),
    /** {@code equals(a, b)} is exact; {@code equals(a, b, epsilon)} is within epsilon. */
    EQUALS(2, 3, Shape.SCALAR, null, (a, b) -> a == b ? 1.0 : 0.0),
    NOTEQUALS(2, 2, Shape.SCALAR, null, (a, b) -> a != b ? 1.0 : 0.0),
    LESSTHAN(2, 2, Shape.SCALAR, null, (a, b) -> a < b ? 1.0 : 0.0),
    LESSTHANOREQUALS(2, 2, Shape.SCALAR, null, (a, b) -> a <= b ? 1.0 : 0.0),
    MORETHAN(2, 2, Shape.SCALAR, null, (a, b) -> a > b ? 1.0 : 0.0),
    MORETHANOREQUALS(2, 2, Shape.SCALAR, null, (a, b) -> a >= b ? 1.0 : 0.0),
    AND(2, 2, Shape.SCALAR, null, (a, b) -> a != 0.0 && b != 0.0 ? 1.0 : 0.0),
    OR(2, 2, Shape.SCALAR, null, (a, b) -> a != 0.0 || b != 0.0 ? 1.0 : 0.0),
    NOT(1, 1, Shape.SCALAR, value -> value == 0.0 ? 1.0 : 0.0),
    IF(3, Integer.MAX_VALUE, Shape.SELECT, null),
    IN(2, Integer.MAX_VALUE, Shape.SCALAR, null),
    SMOOTH(1, 4, Shape.SMOOTH, null),
    VEC2(1, 4, Shape.CONSTRUCT, null),
    VEC3(1, 4, Shape.CONSTRUCT, null),
    VEC4(1, 4, Shape.CONSTRUCT, null);

    /** How a function types its arguments. */
    enum Shape {
        /** Every argument component-wise, a scalar widening to the others' width. */
        LANES,
        /** Scalars only. */
        SCALAR,
        /** {@code if}: scalar conditions, values of one width. */
        SELECT,
        /** {@code smooth}: a component-wise value and two scalar half lives, after its id is dropped. */
        SMOOTH,
        /** {@code vecN}: components in order, or one scalar widened. */
        CONSTRUCT
    }

    private final int minimum;
    private final int maximum;
    private final Shape shape;
    private final DoubleUnaryOperator unary;
    private final DoubleBinaryOperator binary;

    PackFunction(int minimum, int maximum, Shape shape, DoubleUnaryOperator unary) {
        this(minimum, maximum, shape, unary, null);
    }

    PackFunction(int minimum, int maximum, Shape shape, DoubleUnaryOperator unary, DoubleBinaryOperator binary) {
        this.minimum = minimum;
        this.maximum = maximum;
        this.shape = shape;
        this.unary = unary;
        this.binary = binary;
    }

    int minimum() {
        return this.minimum;
    }

    int maximum() {
        return this.maximum;
    }

    Shape shape() {
        return this.shape;
    }

    /** The operation applied to a single argument, or null when the function takes more. */
    DoubleUnaryOperator unary() {
        return this.unary;
    }

    /** The operation applied to the first two arguments, or null when the function has none. */
    DoubleBinaryOperator binary() {
        return this.binary;
    }

    static PackFunction named(String name) {
        for (PackFunction function : values()) {
            if (function.name().equalsIgnoreCase(name)) {
                return function;
            }
        }
        return null;
    }
}
