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
 * <p>Holds the pack's authored custom values: the declarations it wrote in
 * {@code shaders.properties}, compiled into {@link PackExpression programs}, ordered so a value
 * that reads another reads a resolved one, and evaluated once per frame into the storage the
 * uniform writer serves. Session state, which is the smoothing, lives in {@link Session} and is
 * dropped on reload.
 *
 * <p>A declaration may take a name Chimera only kept as a placeholder for it. It may not take a
 * genuine engine input: those are the host's to answer, and a pack that redefines one is broken
 * rather than accommodated. Anything else is the pack's own name and is admitted as it stands.
 */
public final class PackRuntimeSettings {
    public static final float DEFAULT_WETNESS_RISE_HALF_LIFE = 600.0f;
    public static final float DEFAULT_WETNESS_FALL_HALF_LIFE = 200.0f;
    public static final float DEFAULT_EYE_BRIGHTNESS_HALF_LIFE = 10.0f;

    private final float wetnessRiseHalfLife;
    private final float wetnessFallHalfLife;
    private final float eyeBrightnessHalfLife;
    private final List<Value> values;
    /** Each value's first storage slot; a vector holds one slot per component. */
    private final Map<String, Integer> indices;
    private final Map<String, Integer> widths;
    private final int slotCount;
    private final Map<String, UniformRegistry.UniformDescriptor> customDescriptors;
    private final Set<String> rejected;
    private final List<String> deviations;

    private PackRuntimeSettings(
            float wetnessRiseHalfLife,
            float wetnessFallHalfLife,
            float eyeBrightnessHalfLife,
            List<Value> values,
            Map<String, UniformRegistry.UniformDescriptor> rejectedDescriptors,
            Set<String> rejected,
            List<String> deviations
    ) {
        this.wetnessRiseHalfLife = wetnessRiseHalfLife;
        this.wetnessFallHalfLife = wetnessFallHalfLife;
        this.eyeBrightnessHalfLife = eyeBrightnessHalfLife;
        this.values = List.copyOf(values);
        Map<String, Integer> indexMap = new TreeMap<>();
        Map<String, Integer> widthMap = new TreeMap<>();
        Map<String, UniformRegistry.UniformDescriptor> descriptors = new TreeMap<>();
        int slot = 0;
        for (Value value : this.values) {
            indexMap.put(value.name(), slot);
            widthMap.put(value.name(), value.width());
            slot += value.width();
            if (value.exposed()) {
                descriptors.put(value.name(), new UniformRegistry.UniformDescriptor(
                        value.name(), glslTypes(value.type()), UniformRegistry.Availability.LIVE,
                        "custom:" + value.name(), UniformRegistry.DefaultPolicy.ZERO));
            }
        }
        if (rejectedDescriptors != null) {
            descriptors.putAll(rejectedDescriptors);
        }
        this.indices = Collections.unmodifiableMap(indexMap);
        this.widths = Collections.unmodifiableMap(widthMap);
        this.slotCount = slot;
        this.customDescriptors = Collections.unmodifiableMap(descriptors);
        this.rejected = rejected == null ? Set.of() : Set.copyOf(rejected);
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
                Map.of(),
                Set.of(),
                List.of());
    }

    static PackRuntimeSettings build(
            List<Declaration> declarations,
            Map<String, String> defaults,
            List<String> deviations
    ) {
        return build(declarations, defaults, deviations, BiomeIds.constants());
    }

    static PackRuntimeSettings build(
            List<Declaration> declarations,
            Map<String, String> defaults,
            List<String> deviations,
            PackExpression.Constants constants
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
        Set<String> rejected = new TreeSet<>();
        // A declared value types every reference to it; anything else is the host's input.
        PackExpression.Widths widths = name -> {
            Declaration declared = byName.get(name);
            return declared != null ? width(declared.type()) : UniformRegistry.expressionWidth(name);
        };
        for (Declaration declaration : byName.values()) {
            if (width(declaration.type()) == 0) {
                result.add("CUSTOM_VALUE_TYPE_UNSUPPORTED:" + declaration.name());
                rejected.add(declaration.name());
                continue;
            }
            if (UniformRegistry.isEngineInput(declaration.name())) {
                result.add("CUSTOM_VALUE_SHADOWS_BUILTIN:" + declaration.name());
                rejected.add(declaration.name());
                continue;
            }
            PackExpression.Program program;
            try {
                program = PackExpression.parse(declaration.expression(), constants, widths);
            } catch (PackExpression.Unsupported failure) {
                result.add(failure.code() + ":" + declaration.name()
                        + (failure.detail().isBlank() ? "" : ":" + failure.detail()));
                rejected.add(declaration.name());
                continue;
            }
            if (program.width() != width(declaration.type())) {
                result.add("CUSTOM_EXPRESSION_TYPE:" + declaration.name() + ":" + declaration.type()
                        + " given width " + program.width());
                rejected.add(declaration.name());
                continue;
            }
            String unknown = unresolved(program, byName.keySet());
            if (unknown != null) {
                result.add("CUSTOM_EXPRESSION_UNKNOWN:" + declaration.name() + ":" + unknown);
                rejected.add(declaration.name());
                continue;
            }
            String unreadable = unreadable(program, byName.keySet());
            if (unreadable != null) {
                result.add("CUSTOM_EXPRESSION_COMPONENT:" + declaration.name() + ":" + unreadable);
                rejected.add(declaration.name());
                continue;
            }
            parsed.put(declaration.name(), new ParsedValue(declaration, program));
        }

        Set<String> visiting = new HashSet<>();
        Set<String> resolved = new HashSet<>();
        List<String> order = new ArrayList<>();
        for (String name : parsed.keySet()) {
            resolve(name, parsed, rejected, visiting, resolved, order, result);
        }

        List<Value> values = new ArrayList<>();
        for (String name : order) {
            if (!rejected.contains(name)) {
                ParsedValue value = parsed.get(name);
                values.add(new Value(value.declaration().name(), value.declaration().type(),
                        value.declaration().exposed(), value.program(),
                        width(value.declaration().type())));
            }
        }
        Map<String, UniformRegistry.UniformDescriptor> rejectedDescriptors = new TreeMap<>();
        for (String name : rejected) {
            Declaration declaration = byName.get(name);
            if (declaration != null && declaration.exposed()) {
                rejectedDescriptors.put(name, new UniformRegistry.UniformDescriptor(
                        name, glslTypes(declaration.type()),
                        UniformRegistry.Availability.REJECTED, "rejected:" + name,
                        UniformRegistry.DefaultPolicy.ZERO));
            }
        }
        return new PackRuntimeSettings(wetnessRise, wetnessFall, eyeBrightness, values,
                rejectedDescriptors, rejected, result);
    }

    /** The first dependency that is neither a declared name nor a served uniform. */
    private static String unresolved(PackExpression.Program program, Set<String> declared) {
        for (String dependency : program.dependencies()) {
            if (!declared.contains(dependency) && UniformRegistry.descriptor(dependency) == null) {
                return dependency;
            }
        }
        return null;
    }

    /**
     * A host vector or matrix the expression reads whose components the host does not serve to
     * expressions. The declaration cannot mean what it says, so it is refused, not zeroed.
     */
    private static String unreadable(PackExpression.Program program, Set<String> declared) {
        for (String dependency : program.dependencies()) {
            if (!declared.contains(dependency) && UniformRegistry.expressionWidth(dependency) > 1
                    && !UniformRegistry.expressionComponentsServed(dependency)) {
                return dependency;
            }
        }
        return null;
    }

    /**
     * GLSL declarations a value may be read through. Iris serves {@code uniform.bool} to a
     * {@code bool} or an {@code int} declaration alike; both are one 32-bit integer in the block.
     */
    private static List<String> glslTypes(String type) {
        return type.equals("bool") ? List.of("int", "bool") : List.of(type);
    }

    /** Components a declared type holds, or 0 for a type the language does not serve. */
    private static int width(String type) {
        return switch (type) {
            case "float", "int", "bool" -> 1;
            case "vec2" -> 2;
            case "vec3" -> 3;
            case "vec4" -> 4;
            default -> 0;
        };
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

    /** Declaration names that could not be compiled: they must not be served as a value. */
    public Set<String> rejected() {
        return rejected;
    }

    public int valueCount() {
        return values.size();
    }

    /** The first storage slot of a value; a vector's components follow it in order. */
    public int indexOf(String name) {
        return indices.getOrDefault(name, -1);
    }

    /** Per-session storage: one smoothing state per value, one slot per value. */
    public static final class Session {
        private final PackExpression.State[] states;
        private final double[] scratch;
        private final float[] output;
        private final double[] lastFinite;
        /** One value's components for the frame, before the finite check stores them. */
        private final double[] lanes = new double[4];
        private final Map<String, String> failures = new TreeMap<>();
        private int frames;

        private Session(List<Value> values, int slots) {
            this.states = new PackExpression.State[values.size()];
            for (int index = 0; index < values.size(); index++) {
                this.states[index] = values.get(index).program().newState();
            }
            this.scratch = new double[slots];
            this.output = new float[slots];
            this.lastFinite = new double[slots];
        }

        /** The frame's evaluated values, one slot per component, in dependency order. */
        public float[] values() {
            return this.output;
        }

        /**
         * Declarations whose expression produced a non-finite result, with the context of the
         * first frame that happened on. A failure here is never silent: it is reported once and
         * the value holds its last finite result rather than an invented one.
         */
        public Map<String, String> failures() {
            return Map.copyOf(this.failures);
        }

        /** Drops every accumulator and value, as a pack reload must. */
        public void reset() {
            for (PackExpression.State state : this.states) {
                state.reset();
            }
            java.util.Arrays.fill(this.scratch, 0.0);
            java.util.Arrays.fill(this.output, 0.0F);
            java.util.Arrays.fill(this.lastFinite, 0.0);
            this.failures.clear();
            this.frames = 0;
        }
    }

    public Session newSession() {
        return new Session(values, slotCount);
    }

    /**
     * Evaluates every valid custom value once, in dependency order, before any program's uniform
     * block is written. A value that reads another reads this frame's result, and a value that
     * smooths folds one frame of its own call site's state.
     */
    public void evaluate(PackExpression.Inputs engine, Session session, float frameDelta) {
        if (session == null) {
            return;
        }
        session.frames++;
        PackExpression.Inputs inputs = this.lookup(engine, session.scratch);
        for (int index = 0; index < values.size(); index++) {
            Value value = values.get(index);
            int base = indices.get(value.name());
            value.program().evaluate(inputs, session.states[index], frameDelta, session.lanes, 0);
            for (int lane = 0; lane < value.width(); lane++) {
                double result = session.lanes[lane];
                int slot = base + lane;
                if (!Double.isFinite(result)) {
                    // Diagnosis over substitution: name the declaration, its expression and the
                    // inputs that produced it, and hold the last finite result rather than
                    // inventing a number. A silent zero here is what DOC-400 item 4 objects to.
                    session.failures.putIfAbsent(value.name(),
                            "expression=" + value.program().source()
                                    + " lane=" + lane
                                    + " frame=" + session.frames
                                    + " frameDelta=" + frameDelta
                                    + " inputs=" + context(value, inputs));
                    result = session.lastFinite[slot];
                } else {
                    session.lastFinite[slot] = result;
                }
                session.scratch[slot] = result;
                session.output[slot] = value.type().equals("int") || value.type().equals("bool")
                        ? (float) ((int) result) : (float) result;
            }
        }
    }

    /** The scalar inputs one expression read, as the values the failure saw them. */
    private static String context(Value value, PackExpression.Inputs inputs) {
        TreeMap<String, Double> named = new TreeMap<>();
        for (String name : value.program().dependencies()) {
            named.put(name, inputs.value(name, -1));
        }
        return named.toString();
    }

    /** Custom values win over the host for their own names; everything else is the host's. */
    private PackExpression.Inputs lookup(PackExpression.Inputs engine, double[] scratch) {
        return (name, component) -> {
            Integer index = indices.get(name);
            if (index == null) {
                return engine.value(name, component);
            }
            int width = widths.get(name);
            return component < 0 ? scratch[index] : component < width ? scratch[index + component] : Double.NaN;
        };
    }

    /** Components a served value holds: 1 for a scalar, 2 to 4 for a vector, 0 when absent. */
    public int widthOf(String name) {
        return widths.getOrDefault(name, 0);
    }

    static Declaration declaration(boolean exposed, String type, String name, String expression) {
        return new Declaration(exposed, type, name, expression);
    }

    /**
     * One authored declaration. {@code exposed} is whether shaders see it: {@code uniform.} lines
     * do, {@code variable.} lines are the pack's own working values.
     */
    record Declaration(boolean exposed, String type, String name, String expression) {
        Declaration {
            type = type == null ? "" : type.trim();
            name = name == null ? "" : name.trim();
            expression = expression == null ? "" : expression.trim();
        }
    }

    private record ParsedValue(Declaration declaration, PackExpression.Program program) {}

    private record Value(String name, String type, boolean exposed, PackExpression.Program program,
                         int width) {}

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
            Set<String> rejected,
            Set<String> visiting,
            Set<String> resolved,
            List<String> order,
            List<String> deviations
    ) {
        if (resolved.contains(name)) {
            return !rejected.contains(name);
        }
        if (!visiting.add(name)) {
            rejected.add(name);
            deviations.add("CUSTOM_VALUE_CYCLE:" + name);
            return false;
        }
        ParsedValue value = parsed.get(name);
        boolean valid = value != null && !rejected.contains(name);
        if (valid) {
            for (String dependency : value.program().dependencies()) {
                // A dependency refused at parse time is still declared, so the host must not
                // answer for it: its dependents are refused too, never evaluated against a zero.
                ParsedValue dependencyValue = parsed.get(dependency);
                if (rejected.contains(dependency) || (dependencyValue != null
                        && !resolve(dependency, parsed, rejected, visiting, resolved, order,
                        deviations))) {
                    rejected.add(name);
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
}
