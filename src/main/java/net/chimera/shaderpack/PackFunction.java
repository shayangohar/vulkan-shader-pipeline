package net.chimera.shaderpack;

import java.util.function.DoubleUnaryOperator;

/**
 * The functions a {@link PackExpression} may call: OptiFine's documented custom-uniform set and
 * Iris's additions, with the arity and argument shape each accepts. A one-argument math function
 * carries its operation; the rest are folded by the call that uses them.
 */
enum PackFunction {
    ABS(1, 1, Shape.LANES, Math::abs),
    SIN(1, 1, Shape.LANES, Math::sin),
    COS(1, 1, Shape.LANES, Math::cos),
    ASIN(1, 1, Shape.LANES, Math::asin),
    ACOS(1, 1, Shape.LANES, Math::acos),
    TAN(1, 1, Shape.LANES, Math::tan),
    /** One argument is {@code atan}; two are {@code atan2}. */
    ATAN(1, 2, Shape.LANES, Math::atan),
    TORAD(1, 1, Shape.LANES, Math::toRadians),
    TODEG(1, 1, Shape.LANES, Math::toDegrees),
    FLOOR(1, 1, Shape.LANES, Math::floor),
    CEIL(1, 1, Shape.LANES, Math::ceil),
    FRAC(1, 1, Shape.LANES, value -> value - Math.floor(value)),
    ROUND(1, 1, Shape.LANES, value -> (double) Math.round(value)),
    SIGNUM(1, 1, Shape.LANES, Math::signum),
    EXP(1, 1, Shape.LANES, Math::exp),
    LOG(1, 1, Shape.LANES, Math::log),
    POW(2, 2, Shape.LANES, null),
    SQRT(1, 1, Shape.LANES, Math::sqrt),
    FMOD(2, 2, Shape.LANES, null),
    RANDOM(0, 0, Shape.SCALAR, null),
    MAX(2, Integer.MAX_VALUE, Shape.LANES, null),
    MIN(2, Integer.MAX_VALUE, Shape.LANES, null),
    CLAMP(3, 3, Shape.LANES, null),
    BETWEEN(3, 3, Shape.SCALAR, null),
    EQUALS(3, 3, Shape.SCALAR, null),
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

    PackFunction(int minimum, int maximum, Shape shape, DoubleUnaryOperator unary) {
        this.minimum = minimum;
        this.maximum = maximum;
        this.shape = shape;
        this.unary = unary;
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

    static PackFunction named(String name) {
        for (PackFunction function : values()) {
            if (function.name().equalsIgnoreCase(name)) {
                return function;
            }
        }
        return null;
    }
}
