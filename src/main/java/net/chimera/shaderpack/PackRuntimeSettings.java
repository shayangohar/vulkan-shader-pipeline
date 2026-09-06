package net.chimera.shaderpack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable, bounded runtime settings for one pack session.
 *
 * <p>This is deliberately smaller than a general expression engine. It only
 * evaluates scalar pack values that are declared in shaders.properties and
 * uses the already captured standard uniform values as inputs.</p>
 */
public final class PackRuntimeSettings {
    public static final float DEFAULT_WETNESS_RISE_HALF_LIFE = 600.0f;
    public static final float DEFAULT_WETNESS_FALL_HALF_LIFE = 200.0f;
    public static final float DEFAULT_EYE_BRIGHTNESS_HALF_LIFE = 10.0f;

    private final float wetnessRiseHalfLife;
    private final float wetnessFallHalfLife;
    private final float eyeBrightnessHalfLife;
    private final List<Value> values;
    private final Map<String, Integer> indices;
    private final Map<String, UniformRegistry.UniformDescriptor> customDescriptors;
    private final List<String> deviations;

    private PackRuntimeSettings(
            float wetnessRiseHalfLife,
            float wetnessFallHalfLife,
            float eyeBrightnessHalfLife,
            List<Value> values,
            List<String> deviations
    ) {
        this.wetnessRiseHalfLife = wetnessRiseHalfLife;
        this.wetnessFallHalfLife = wetnessFallHalfLife;
        this.eyeBrightnessHalfLife = eyeBrightnessHalfLife;
        this.values = List.copyOf(values);
        Map<String, Integer> indexMap = new TreeMap<>();
        Map<String, UniformRegistry.UniformDescriptor> descriptors = new TreeMap<>();
        for (int index = 0; index < this.values.size(); index++) {
            Value value = this.values.get(index);
            indexMap.put(value.name(), index);
            if (value.exposed()) {
                String glslType = value.type().equals("bool") ? "int" : value.type();
                descriptors.put(value.name(), new UniformRegistry.UniformDescriptor(
                        value.name(), List.of(glslType), UniformRegistry.Availability.LIVE,
                        "custom:" + value.name(), UniformRegistry.DefaultPolicy.ZERO));
            }
        }
        this.indices = Collections.unmodifiableMap(indexMap);
        this.customDescriptors = Collections.unmodifiableMap(descriptors);
        this.deviations = deviations == null
                ? List.of() : deviations.stream().filter(value -> value != null && !value.isBlank())
                .distinct().sorted().toList();
    }

    public static PackRuntimeSettings empty() {
        return new PackRuntimeSettings(
                DEFAULT_WETNESS_RISE_HALF_LIFE,
                DEFAULT_WETNESS_FALL_HALF_LIFE,
                DEFAULT_EYE_BRIGHTNESS_HALF_LIFE,
                List.of(),
                List.of());
    }

    static PackRuntimeSettings build(
            List<Declaration> declarations,
            Map<String, String> defaults,
            List<String> deviations
    ) {
        List<String> result = new ArrayList<>(deviations == null ? List.of() : deviations);
        float wetnessRise = numeric(defaults, "wetnessHalflife",
                DEFAULT_WETNESS_RISE_HALF_LIFE, result, "wetness");
        float wetnessFall = DEFAULT_WETNESS_FALL_HALF_LIFE;
        float eyeBrightness = numeric(defaults, "eyeBrightnessHalflife",
                DEFAULT_EYE_BRIGHTNESS_HALF_LIFE, result, "eyeBrightness");

        Map<String, Declaration> byName = new TreeMap<>();
        if (declarations != null) {
            for (Declaration declaration : declarations) {
                if (declaration != null) {
                    byName.put(declaration.name(), declaration);
                }
            }
        }

        Map<String, ParsedValue> parsed = new TreeMap<>();
        Set<String> invalid = new TreeSet<>();
        for (Declaration declaration : byName.values()) {
            if (!isScalarType(declaration.type())) {
                result.add("CUSTOM_VALUE_TYPE_UNSUPPORTED:" + declaration.name());
                invalid.add(declaration.name());
                continue;
            }
            if (UniformRegistry.descriptor(declaration.name()) != null) {
                result.add("CUSTOM_VALUE_SHADOWS_BUILTIN:" + declaration.name());
                invalid.add(declaration.name());
                continue;
            }
            try {
                Expression expression = new Parser(declaration.expression()).parse();
                for (String dependency : expression.dependencies()) {
                    if (!byName.containsKey(dependency)
                            && UniformRegistry.descriptor(dependency) == null) {
                        result.add("CUSTOM_EXPRESSION_UNKNOWN:" + declaration.name()
                                + ":" + dependency);
                        invalid.add(declaration.name());
                    }
                }
                parsed.put(declaration.name(), new ParsedValue(declaration, expression));
            } catch (ParseFailure failure) {
                result.add(failure.code() + ":" + declaration.name()
                        + (failure.detail().isBlank() ? "" : ":" + failure.detail()));
                invalid.add(declaration.name());
            }
        }

        Set<String> visiting = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        List<String> order = new ArrayList<>();
        for (String name : parsed.keySet()) {
            resolve(name, parsed, invalid, visiting, resolved, order, result);
        }

        List<Value> values = new ArrayList<>();
        for (String name : order) {
            if (!invalid.contains(name)) {
                ParsedValue value = parsed.get(name);
                values.add(new Value(value.declaration().name(), value.declaration().type(),
                        value.declaration().exposed(), value.expression()));
            }
        }
        return new PackRuntimeSettings(wetnessRise, wetnessFall, eyeBrightness, values, result);
    }

    public float wetnessRiseHalfLife() {
        return wetnessRiseHalfLife;
    }

    public float wetnessFallHalfLife() {
        return wetnessFallHalfLife;
    }

    public float eyeBrightnessHalfLife() {
        return eyeBrightnessHalfLife;
    }

    public List<String> deviations() {
        return deviations;
    }

    public Map<String, UniformRegistry.UniformDescriptor> customDescriptors() {
        return customDescriptors;
    }

    public int valueCount() {
        return values.size();
    }

    public int indexOf(String name) {
        return indices.getOrDefault(name, -1);
    }

    /** Evaluate every valid custom value once, in dependency order. */
    public void evaluate(ValueLookup lookup, double[] scratch, float[] output) {
        if (scratch.length < values.size() || output.length < values.size()) {
            throw new IllegalArgumentException("custom value storage is too small");
        }
        for (int index = 0; index < values.size(); index++) {
            Value value = values.get(index);
            double result = value.expression().evaluate(lookup, indices, scratch);
            if (!Double.isFinite(result)) {
                result = 0.0;
            }
            scratch[index] = result;
            output[index] = value.type().equals("int") || value.type().equals("bool")
                    ? (float) ((int) result) : (float) result;
        }
    }

    @FunctionalInterface
    public interface ValueLookup {
        double value(String name);
    }

    static Declaration declaration(boolean exposed, String type, String name, String expression) {
        return new Declaration(exposed, type, name, expression);
    }

    record Declaration(boolean exposed, String type, String name, String expression) {
        Declaration {
            type = type == null ? "" : type.trim();
            name = name == null ? "" : name.trim();
            expression = expression == null ? "" : expression.trim();
        }
    }

    private record ParsedValue(Declaration declaration, Expression expression) {}

    private record Value(String name, String type, boolean exposed, Expression expression) {}

    private static boolean isScalarType(String type) {
        return type.equals("float") || type.equals("int") || type.equals("bool");
    }

    private static float numeric(
            Map<String, String> defaults,
            String name,
            float fallback,
            List<String> deviations,
            String label
    ) {
        String value = defaults == null ? null : defaults.get(name);
        if (value == null || value.isBlank()) {
            deviations.add("SMOOTHING_DEFAULTED:" + label);
            return fallback;
        }
        try {
            float parsed = Float.parseFloat(value.replace("f", "").replace("F", ""));
            if (!Float.isFinite(parsed) || parsed < 0.0f) {
                deviations.add("SMOOTHING_DEFAULTED:" + label);
                return fallback;
            }
            deviations.add("SMOOTHING_APPLIED:" + label);
            return parsed;
        } catch (NumberFormatException failure) {
            deviations.add("SMOOTHING_DEFAULTED:" + label);
            return fallback;
        }
    }

    private static boolean resolve(
            String name,
            Map<String, ParsedValue> parsed,
            Set<String> invalid,
            Set<String> visiting,
            Set<String> resolved,
            List<String> order,
            List<String> deviations
    ) {
        if (resolved.contains(name)) {
            return !invalid.contains(name);
        }
        if (!visiting.add(name)) {
            invalid.add(name);
            deviations.add("CUSTOM_VALUE_CYCLE:" + name);
            return false;
        }
        ParsedValue value = parsed.get(name);
        boolean valid = value != null && !invalid.contains(name);
        if (valid) {
            for (String dependency : value.expression().dependencies()) {
                ParsedValue dependencyValue = parsed.get(dependency);
                if (dependencyValue != null
                        && !resolve(dependency, parsed, invalid, visiting, resolved, order, deviations)) {
                    invalid.add(name);
                    deviations.add("CUSTOM_VALUE_DEPENDENCY_INVALID:" + name);
                    valid = false;
                }
            }
        }
        visiting.remove(name);
        resolved.add(name);
        if (valid) {
            order.add(name);
        }
        return valid;
    }

    private interface Expression {
        double evaluate(ValueLookup lookup, Map<String, Integer> indices, double[] values);

        Set<String> dependencies();
    }

    private record Literal(double value) implements Expression {
        @Override
        public double evaluate(ValueLookup lookup, Map<String, Integer> indices, double[] values) {
            return value;
        }

        @Override
        public Set<String> dependencies() {
            return Set.of();
        }
    }

    private record Reference(String name) implements Expression {
        @Override
        public double evaluate(ValueLookup lookup, Map<String, Integer> indices, double[] values) {
            Integer index = indices.get(name);
            return index == null ? lookup.value(name) : values[index];
        }

        @Override
        public Set<String> dependencies() {
            return Set.of(name);
        }
    }

    private record Unary(char operator, Expression value) implements Expression {
        @Override
        public double evaluate(ValueLookup lookup, Map<String, Integer> indices, double[] values) {
            double result = value.evaluate(lookup, indices, values);
            return operator == '!' ? result == 0.0 ? 1.0 : 0.0
                    : operator == '-' ? -result : result;
        }

        @Override
        public Set<String> dependencies() {
            return value.dependencies();
        }
    }

    private record Binary(String operator, Expression left, Expression right) implements Expression {
        @Override
        public double evaluate(ValueLookup lookup, Map<String, Integer> indices, double[] values) {
            double a = left.evaluate(lookup, indices, values);
            double b = right.evaluate(lookup, indices, values);
            return switch (operator) {
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
                default -> 0.0;
            };
        }

        @Override
        public Set<String> dependencies() {
            Set<String> result = new TreeSet<>(left.dependencies());
            result.addAll(right.dependencies());
            return result;
        }
    }

    private record Conditional(Expression condition, Expression whenTrue, Expression whenFalse)
            implements Expression {
        @Override
        public double evaluate(ValueLookup lookup, Map<String, Integer> indices, double[] values) {
            return (condition.evaluate(lookup, indices, values) != 0.0 ? whenTrue : whenFalse)
                    .evaluate(lookup, indices, values);
        }

        @Override
        public Set<String> dependencies() {
            Set<String> result = new TreeSet<>(condition.dependencies());
            result.addAll(whenTrue.dependencies());
            result.addAll(whenFalse.dependencies());
            return result;
        }
    }

    private static final class ParseFailure extends RuntimeException {
        private final String code;
        private final String detail;

        private ParseFailure(String code, String detail) {
            super(code + (detail == null || detail.isBlank() ? "" : ":" + detail));
            this.code = code;
            this.detail = detail == null ? "" : detail;
        }

        private String code() {
            return code;
        }

        private String detail() {
            return detail;
        }
    }

    private static final class Parser {
        private final String source;
        private int index;
        private int depth;

        private Parser(String source) {
            this.source = source == null ? "" : source;
        }

        private Expression parse() {
            Expression result = conditional();
            skip();
            if (index != source.length()) {
                throw new ParseFailure("CUSTOM_EXPRESSION_UNSUPPORTED", source.substring(index));
            }
            return result;
        }

        private Expression conditional() {
            Expression result = or();
            if (take("?")) {
                Expression whenTrue = conditional();
                require(":");
                result = new Conditional(result, whenTrue, conditional());
            }
            return result;
        }

        private Expression or() {
            Expression result = and();
            while (take("||")) result = new Binary("||", result, and());
            return result;
        }

        private Expression and() {
            Expression result = equality();
            while (take("&&")) result = new Binary("&&", result, equality());
            return result;
        }

        private Expression equality() {
            Expression result = relation();
            while (true) {
                String operator = operator("==", "!=");
                if (operator == null) return result;
                result = new Binary(operator, result, relation());
            }
        }

        private Expression relation() {
            Expression result = add();
            while (true) {
                String operator = operator(">=", "<=", ">", "<");
                if (operator == null) return result;
                result = new Binary(operator, result, add());
            }
        }

        private Expression add() {
            Expression result = multiply();
            while (true) {
                String operator = operator("+", "-");
                if (operator == null) return result;
                result = new Binary(operator, result, multiply());
            }
        }

        private Expression multiply() {
            Expression result = unary();
            while (true) {
                String operator = operator("*", "/", "%");
                if (operator == null) return result;
                result = new Binary(operator, result, unary());
            }
        }

        private Expression unary() {
            enter();
            try {
                if (take("!")) return new Unary('!', unary());
                if (take("-")) return new Unary('-', unary());
                if (take("+")) return unary();
                if (take("(")) {
                    Expression result = conditional();
                    require(")");
                    return result;
                }
                if (takeWord("true")) return new Literal(1.0);
                if (takeWord("false")) return new Literal(0.0);
                if (index < source.length()
                        && (Character.isDigit(source.charAt(index)) || source.charAt(index) == '.')) {
                    return new Literal(number());
                }
                String name = identifier();
                skip();
                if (index < source.length() && source.charAt(index) == '(') {
                    throw new ParseFailure("CUSTOM_EXPRESSION_UNSUPPORTED", name);
                }
                if (UniformRegistry.descriptor(name) == null) {
                    // Custom references are validated in build after parsing.
                }
                return new Reference(name);
            } finally {
                depth--;
            }
        }

        private double number() {
            int start = index;
            while (index < source.length()
                    && (Character.isDigit(source.charAt(index)) || ".eEfF+-".indexOf(source.charAt(index)) >= 0)) {
                char current = source.charAt(index);
                if ((current == '+' || current == '-') && index > start
                        && source.charAt(index - 1) != 'e' && source.charAt(index - 1) != 'E') {
                    break;
                }
                index++;
            }
            try {
                return Double.parseDouble(source.substring(start, index).replace("f", "").replace("F", ""));
            } catch (NumberFormatException failure) {
                throw new ParseFailure("CUSTOM_EXPRESSION_UNSUPPORTED", source.substring(start, index));
            }
        }

        private String identifier() {
            skip();
            int start = index;
            if (index >= source.length()
                    || !(Character.isLetter(source.charAt(index)) || source.charAt(index) == '_')) {
                throw new ParseFailure("CUSTOM_EXPRESSION_UNSUPPORTED", "identifier");
            }
            index++;
            while (index < source.length()
                    && (Character.isLetterOrDigit(source.charAt(index)) || source.charAt(index) == '_')) {
                index++;
            }
            return source.substring(start, index);
        }

        private String operator(String... operators) {
            for (String operator : operators) {
                if (take(operator)) return operator;
            }
            return null;
        }

        private boolean take(String token) {
            skip();
            if (source.startsWith(token, index)) {
                index += token.length();
                return true;
            }
            return false;
        }

        private boolean takeWord(String word) {
            skip();
            if (!source.startsWith(word, index)) return false;
            int end = index + word.length();
            if (end < source.length()
                    && (Character.isLetterOrDigit(source.charAt(end)) || source.charAt(end) == '_')) {
                return false;
            }
            index = end;
            return true;
        }

        private void require(String token) {
            if (!take(token)) throw new ParseFailure("CUSTOM_EXPRESSION_UNSUPPORTED", token);
        }

        private void enter() {
            if (++depth > 64) throw new ParseFailure("CUSTOM_EXPRESSION_TOO_DEEP", "");
        }

        private void skip() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) index++;
        }
    }
}
