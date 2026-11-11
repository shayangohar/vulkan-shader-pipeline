package net.chimera.shaderpack;

import net.chimera.render.shader.GlslCompiler;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pack modules must read zero from locals the authored GLSL never wrote
 * (Complementary parallaxTexDepth), as on the GL drivers packs target.
 * Compiles real GLSL, applies the initializer, and checks every plain-data
 * function variable carries an OpConstantNull of its own type while opaque
 * handles, initialized locals and the id bound stay coherent. The same
 * modules tell SpirvOutputWrites which output locations they write.
 */
final class SpirvLocalInitializerTest {
    @Test
    void localInitializers() {
        String source = """
                #version 460
                layout(binding = 0) uniform sampler2D tex;
                layout(location = 0) in vec2 uv;
                layout(location = 0) out vec4 color;
                struct Hit { float depth; vec2 at; };
                vec4 look(sampler2D s) { return texture(s, uv); }
                void march(inout float depth) { if (depth <= 1.0) depth = look(tex).a; }
                void main() {
                    float fade, depth;
                    vec3 trace;
                    mat2 basis;
                    Hit hit;
                    float lit = 1.0;
                    march(depth);
                    color = vec4(depth + fade + trace.x + basis[0][0] + hit.depth, lit, 0.0, 1.0);
                }
                """;
        int[] original = words(compile(source));
        int[] initialized = SpirvLocalInitializer.apply(original);
        assertTrue(initialized != original, "uninitialized pack locals were left undefined");
        assertTrue(initialized[3] > original[3], "new null constants must extend the id bound");
        // lit, and glslang's copy of depth for march's inout parameter, are written whole before anything reads them.
        verifyInitializers(initialized, Set.of("lit", "param"));
        assertTrue(SpirvLocalInitializer.apply(initialized) == initialized, "initializer is not idempotent");

        // A module with no local, private global or output that could be read unwritten is left byte-identical.
        String nothing = "#version 460\nlayout(location = 0) in vec2 uv;\n"
                + "void main() { float edge = 2.0; if (uv.x > edge) discard; }\n";
        int[] clean = words(compile(nothing));
        assertTrue(SpirvLocalInitializer.apply(clean) == clean, "a module with nothing to initialize changed");
    }

    /**
     * Escheridia's composite5 returns before writing its reflection history over the sky. Under
     * NVIDIA's GL compiler that output, like a private global read before it is written, starts
     * at zero, so both get a null initializer declared ahead of them. The colour output main
     * writes first, a built-in output and an input are left alone.
     */
    @Test
    void moduleScopeInitializers() {
        String source = """
                #version 460
                layout(location = 0) in vec2 uv;
                layout(location = 0) out vec4 color;
                layout(location = 1) out vec4 history;
                float accumulated;
                void main() {
                    color = vec4(uv, 0.0, 1.0);
                    if (uv.x > 0.5) return;
                    accumulated += uv.y;
                    history = vec4(accumulated);
                    gl_FragDepth = uv.y;
                }
                """;
        int[] initialized = SpirvLocalInitializer.apply(words(compile(source)));
        Set<Integer> locations = new HashSet<>();
        Set<Integer> builtIns = new HashSet<>();
        Set<Integer> declaredNulls = new HashSet<>();
        Map<Integer, Integer> initializers = new HashMap<>();
        Map<Integer, Integer> storage = new HashMap<>();
        for (int at = 5; at < initialized.length; at += initialized[at] >>> 16) {
            int opcode = initialized[at] & 0xFFFF;
            if (opcode == 71 && initialized[at + 2] == 30) locations.add(initialized[at + 1]);
            if (opcode == 71 && initialized[at + 2] == 11) builtIns.add(initialized[at + 1]);
            if (opcode == 46) declaredNulls.add(initialized[at + 2]);
            if (opcode == 59 && initialized[at + 3] != 7) {
                int id = initialized[at + 2];
                storage.put(id, initialized[at + 3]);
                if (initialized[at] >>> 16 == 5) {
                    assertTrue(declaredNulls.contains(initialized[at + 4]),
                            "%" + id + "'s initializer is not a null constant declared ahead of it");
                    initializers.put(id, initialized[at + 4]);
                }
            }
        }
        Map<String, Integer> names = names(initialized);
        Set<Integer> zeroed = Set.of(names.get("accumulated"), names.get("history"));
        storage.forEach((id, kind) -> assertEquals(zeroed.contains(id), initializers.containsKey(id),
                "%" + id + " in storage class " + kind + (builtIns.contains(id) ? " (built-in)" : "")));
        assertEquals(2, storage.entrySet().stream()
                .filter(entry -> entry.getValue() == 3 && locations.contains(entry.getKey())).count(),
                "fixture lost its located outputs");
        assertTrue(storage.containsValue(6) && !builtIns.isEmpty(), "fixture lost its private global or built-in");
        assertTrue(SpirvLocalInitializer.apply(initialized) == initialized, "initializer is not idempotent");
    }

    /**
     * BSL's shadowcomp copies its light and tint colour tables into private globals at the top of
     * main, where glslang stores a global's initializer. The zero would never be seen, and NVIDIA
     * then fills the tables per invocation (33 ms for the dispatch, against 0.2 ms), so they keep no
     * initializer. A global main writes only in part, or only after a call that reads it, does.
     */
    @Test
    void variablesWrittenFirst() {
        String source = """
                #version 460
                layout(location = 0) in vec2 uv;
                layout(location = 0) out vec4 color;
                vec3 tints[3] = vec3[](vec3(1.0, 0.5, 0.5), vec3(0.5, 1.0, 0.5), vec3(0.5, 0.5, 1.0));
                vec2 corner;
                float late;
                float bump() { return late + 1.0; }
                float lookup(int i) { return tints[i].x + corner.y; }
                void main() {
                    corner.x = uv.y;
                    late = bump();
                    color = vec4(lookup(int(uv.x * 2.0)) + late);
                }
                """;
        int[] initialized = SpirvLocalInitializer.apply(words(compile(source)));
        Map<String, Integer> names = names(initialized);
        Map<Integer, Integer> wordCounts = new HashMap<>();
        for (int at = 5; at < initialized.length; at += initialized[at] >>> 16) {
            if ((initialized[at] & 0xFFFF) == 59) wordCounts.put(initialized[at + 2], initialized[at] >>> 16);
        }
        assertEquals(4, wordCounts.get(names.get("tints")),
                "the colour table main writes first was given an initializer");
        assertEquals(5, wordCounts.get(names.get("corner")), "a global main writes only in part lost its initializer");
        assertEquals(5, wordCounts.get(names.get("late")),
                "a global a call reads before main writes it lost its initializer");
        assertTrue(SpirvLocalInitializer.apply(initialized) == initialized, "initializer is not idempotent");
    }

    /**
     * BSL's world programs store their light-colour table after the short-circuit test of an
     * earlier initializer, and zeroing it first made each terrain draw ten times slower. A store
     * that comes first on every path to every read is enough; a variable written on one path
     * only, or an output an early return leaves unwritten, still starts at zero.
     */
    @Test
    void storesAfterBranches() {
        String source = """
                #version 460
                layout(location = 0) in vec3 pos;
                layout(location = 1) flat in int held;
                layout(location = 0) out vec4 color;
                layout(location = 1) out vec4 glow;
                layout(binding = 0) uniform Frame { float timeAngle; };
                void main() {
                    float side = 1.0;
                    if (timeAngle < 0.25 || timeAngle > 0.75) side = -1.0;
                    vec3 lightColors[4] = vec3[](vec3(4.0, 1.0, 1.0), vec3(1.0, 4.0, 1.0),
                                                 vec3(1.0, 1.0, 4.0), vec3(4.0));
                    float fade;
                    if (held > 2) fade = 0.5;
                    vec3 light = vec3(side * fade);
                    if (held >= 1 && held <= 4) light += lightColors[held - 1];
                    color = vec4(light * pos, 1.0);
                    if (held == 0) return;
                    glow = vec4(light, 1.0);
                }
                """;
        int[] initialized = SpirvLocalInitializer.apply(words(compile(source)));
        Map<String, Integer> names = names(initialized);
        Map<Integer, Integer> wordCounts = new HashMap<>();
        int firstLabel = -1;
        int tableStore = -1;
        for (int at = 5; at < initialized.length; at += initialized[at] >>> 16) {
            int opcode = initialized[at] & 0xFFFF;
            if (opcode == 59) wordCounts.put(initialized[at + 2], initialized[at] >>> 16);
            if (opcode == 248 && firstLabel < 0) firstLabel = at;
            if (opcode == 62 && initialized[at + 1] == names.get("lightColors")) tableStore = at;
        }
        int secondLabel = firstLabel;
        do secondLabel += initialized[secondLabel] >>> 16; while ((initialized[secondLabel] & 0xFFFF) != 248);
        assertTrue(tableStore > secondLabel, "the table is stored in the first block; the fixture lost its branch");
        assertEquals(4, wordCounts.get(names.get("lightColors")), "the table stored before its reads has one");
        assertEquals(4, wordCounts.get(names.get("light")), "a local stored after branches, before its reads, has one");
        assertEquals(5, wordCounts.get(names.get("fade")), "a local written on one path only lost its initializer");
        assertEquals(4, wordCounts.get(names.get("color")), "an output written before every return has an initializer");
        assertEquals(5, wordCounts.get(names.get("glow")), "an output an early return leaves unwritten lost its one");
        assertTrue(SpirvLocalInitializer.apply(initialized) == initialized, "initializer is not idempotent");
    }

    /** Each OpName's string and target; the first target where a name repeats. */
    private static Map<String, Integer> names(int[] words) {
        Map<String, Integer> names = new HashMap<>();
        for (int at = 5; at < words.length; at += words[at] >>> 16) {
            int count = words[at] >>> 16;
            if ((words[at] & 0xFFFF) != 5) continue;
            ByteBuffer bytes = ByteBuffer.allocate((count - 2) * 4).order(ByteOrder.LITTLE_ENDIAN);
            bytes.asIntBuffer().put(words, at + 2, count - 2);
            int end = 0;
            while (end < bytes.capacity() && bytes.get(end) != 0) end++;
            names.putIfAbsent(new String(bytes.array(), 0, end, StandardCharsets.UTF_8), words[at + 1]);
        }
        return names;
    }

    /**
     * Ebin's held light: {@code falloff} is written only inside the light's radius, and the
     * optimizer that compiles pack modules (GlslCompiler.optimize) turns the other path's read
     * into an OpUndef. Each plain undef must come back as a null constant with its id and type,
     * declared ahead of every function.
     */
    @Test
    void optimizedUndefs() {
        String source = """
                #version 460
                layout(location = 0) in vec3 lightPos;
                layout(location = 1) in float torch;
                layout(location = 2) flat in float held;
                layout(location = 0) out vec4 color;
                float heldLight(vec3 p, float light) {
                    float falloff;
                    if (length(p) < light) falloff = mix(clamp(light, 0.0, 1.0), 0.0, length(p) / light);
                    return falloff;
                }
                void main() {
                    color = vec4(2.0 * pow(max(torch, heldLight(lightPos, held)), 5.06));
                }
                """;
        int[] original = words(compile(source, true));
        Map<Integer, Integer> undefs = undefTypes(original);
        assertTrue(!undefs.isEmpty(), "the optimizer kept falloff in memory; the fixture no longer reaches OpUndef");
        int[] initialized = SpirvLocalInitializer.apply(original);
        assertTrue(undefTypes(initialized).isEmpty(), "plain undefs survived");
        Map<Integer, Integer> nulls = new HashMap<>();
        boolean inFunction = false;
        int at = 5;
        for (; at < initialized.length; at += initialized[at] >>> 16) {
            int opcode = initialized[at] & 0xFFFF;
            assertTrue(initialized[at] >>> 16 > 0, "malformed instruction at word " + at);
            if (opcode == 54) inFunction = true;
            if (opcode == 46) {
                assertTrue(!inFunction, "null constant %" + initialized[at + 2] + " declared inside a function");
                nulls.put(initialized[at + 2], initialized[at + 1]);
            }
        }
        assertTrue(at == initialized.length, "instruction stream does not end at the module end");
        undefs.forEach((id, type) -> assertTrue(type.equals(nulls.get(id)),
                "undef %" + id + " is not a null constant of its type"));
        assertTrue(SpirvLocalInitializer.apply(initialized) == initialized, "initializer is not idempotent");
    }

    /** Each OpUndef's result id and type. */
    private static Map<Integer, Integer> undefTypes(int[] words) {
        Map<Integer, Integer> undefs = new HashMap<>();
        for (int at = 5; at < words.length; at += words[at] >>> 16) {
            if ((words[at] & 0xFFFF) == 1) undefs.put(words[at + 2], words[at + 1]);
        }
        return undefs;
    }

    /**
     * BSL's value-noise hash: the arithmetic feeding sin, including the corner
     * offset that reaches it through a function parameter, carries
     * NoContraction ahead of the types; unrelated arithmetic keeps contraction.
     */
    @Test
    void trigPrecision() {
        String source = """
                #version 460
                layout(location = 0) in vec2 p;
                layout(location = 0) out vec4 color;
                float hash(vec2 q) { return fract(sin(dot(q, vec2(12.9898, 4.1414))) * 43758.5453); }
                void main() {
                    vec2 flr = floor(p);
                    float unrelated = p.x * 3.0 + p.y * 5.0;
                    color = vec4(hash(flr) + hash(flr + vec2(1.0, 0.0)), unrelated, 0.0, 1.0);
                }
                """;
        int[] original = words(compile(source));
        int[] decorated = SpirvTrigPrecision.apply(original);
        assertTrue(decorated != original, "the hash arithmetic was left contractible");
        PrecisionScan scan = PrecisionScan.of(decorated);
        assertTrue(decorated[3] == original[3], "the id bound must not change");
        assertTrue(!scan.dots().isEmpty() && scan.noContraction().containsAll(scan.dots()),
                "the hash dot product is contractible");
        assertTrue(!scan.vectorAdds().isEmpty() && scan.noContraction().containsAll(scan.vectorAdds()),
                "the lattice corner offset reaching the hash through a parameter is contractible");
        assertTrue(scan.noContraction().size() < scan.arithmetic().size(), "arithmetic unrelated to sin was decorated");
    }

    /** Result ids of a module's float arithmetic, dot products and vec2 adds, and the ids it marks NoContraction. */
    private record PrecisionScan(Set<Integer> arithmetic, Set<Integer> dots, Set<Integer> vectorAdds,
                                 Set<Integer> noContraction) {
        /** Walks the instruction stream, checking it is well formed and decorates ahead of the types. */
        static PrecisionScan of(int[] decorated) {
            var scan = new PrecisionScan(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());
            int vec2Type = -1;
            int floatType = -1;
            int firstType = -1;
            int at = 5;
            for (; at < decorated.length; at += decorated[at] >>> 16) {
                int opcode = decorated[at] & 0xFFFF;
                int count = decorated[at] >>> 16;
                assertTrue(count > 0, "malformed instruction at word " + at);
                if (firstType < 0 && isTypeDeclaration(opcode)) firstType = at;
                if (opcode == 22) floatType = decorated[at + 1];
                if (opcode == 23 && decorated[at + 2] == floatType && decorated[at + 3] == 2) {
                    vec2Type = decorated[at + 1];
                }
                if (opcode == 71 && decorated[at + 2] == 42) {
                    assertTrue(firstType < 0, "NoContraction decoration follows a type declaration");
                    scan.noContraction.add(decorated[at + 1]);
                }
                if (SpirvTrigPrecision.FLOAT_ARITHMETIC.contains(opcode)) scan.arithmetic.add(decorated[at + 2]);
                if (opcode == 148) scan.dots.add(decorated[at + 2]);
                if (opcode == 129 && decorated[at + 1] == vec2Type) scan.vectorAdds.add(decorated[at + 2]);
            }
            assertTrue(at == decorated.length, "instruction stream does not end at the module end");
            return scan;
        }

        /** OpTypeVoid (19) through OpTypeForwardPointer (39). */
        private static boolean isTypeDeclaration(int opcode) {
            return opcode >= 19 && opcode <= 39;
        }
    }

    /** Every function variable but those named in {@code writtenFirst} carries a null initializer of its type. */
    private static void verifyInitializers(int[] words, Set<String> writtenFirst) {
        Map<Integer, Integer> pointee = new HashMap<>();
        Map<Integer, Integer> nullType = new HashMap<>();
        Set<Integer> uninitialized = new HashSet<>();
        names(words).forEach((name, id) -> { if (writtenFirst.contains(name)) uninitialized.add(id); });
        assertEquals(writtenFirst.size(), uninitialized.size(), "fixture lost a variable it writes first");
        int functionVariables = 0;
        int at = 5;
        for (; at < words.length; at += words[at] >>> 16) {
            int opcode = words[at] & 0xFFFF;
            int count = words[at] >>> 16;
            assertTrue(count > 0, "malformed instruction at word " + at);
            if (opcode == 46) assertTrue(count == 3, "OpConstantNull must be three words");
            if (opcode == 32 && words[at + 2] == 7) pointee.put(words[at + 1], words[at + 3]);
            if (opcode == 46) nullType.put(words[at + 2], words[at + 1]);
            if (opcode == 59 && words[at + 3] == 7) {
                functionVariables++;
                boolean first = uninitialized.contains(words[at + 2]);
                assertTrue(count == (first ? 4 : 5), "function variable %" + words[at + 2]
                        + (first ? " is written first but has an initializer" : " has no initializer"));
                if (first) continue;
                Integer initializer = words[at + 4];
                Integer type = nullType.get(initializer);
                if (type != null) {
                    assertTrue(type.equals(pointee.get(words[at + 1])),
                            "null initializer type differs from variable %" + words[at + 2]);
                }
            }
        }
        assertTrue(at == words.length, "instruction stream does not end at the module end");
        assertTrue(functionVariables >= 6, "fixture lost its function variables: " + functionVariables);
    }

    /** A shadow program that writes shadowcolor0 alone leaves location 1 unwritten. */
    @Test
    void declaredButUnwrittenOutputs() {
        assertEquals(0b01, written("""
                #version 450
                layout(location = 0) out vec4 chimeraShadowColor0;
                layout(location = 1) out vec4 chimeraShadowColor1;
                void main() { chimeraShadowColor0 = vec4(1.0); }
                """));
    }

    /** Writes through a component, an array element, a branch and an out parameter all count. */
    @Test
    void writesInEveryForm() {
        assertEquals(0b1111, written("""
                #version 450
                layout(location = 0) out vec4 color;
                layout(location = 1) out vec4 normal;
                layout(location = 2) out vec4 extra[2];
                layout(location = 0) in float side;
                void fill(out vec4 value) { value = vec4(side); }
                void main() {
                    color.rgb = vec3(side);
                    if (side > 0.5) fill(normal);
                    extra[1] = vec4(0.0);
                }
                """));
    }

    /**
     * An emit sends every output's current value as the vertex, so it reads them all: an output a
     * geometry stage stores only after its first emit starts from zero, as GL's would, and one it
     * stores before every emit needs no initializer. That holds for EmitStreamVertex, optimized or
     * not, and for an emit in a helper that main calls. Only a return counted as a read, which
     * GeometryStageBridge's keep helper happened to cover.
     */
    @Test
    void anEmitReadsEveryOutput() {
        String helper = "void emitAt(vec4 position) { gl_Position = position; %s; }\n";
        for (String emit : List.of("EmitVertex()", "EmitStreamVertex(0)")) {
            String layout = emit.startsWith("EmitStream") ? "points" : "triangle_strip";
            String direct = """
                    #version 460
                    layout(triangles) in;
                    layout(%s, max_vertices = 2) out;
                    layout(location = 0) out vec4 late;
                    layout(location = 1) out vec4 early;
                    void main() {
                        early = vec4(1.0);
                        gl_Position = gl_in[0].gl_Position;
                        %s;
                        late = vec4(2.0);
                        early = vec4(3.0);
                        gl_Position = gl_in[1].gl_Position;
                        %s;
                    }
                    """.formatted(layout, emit, emit);
            String called = """
                    #version 460
                    layout(triangles) in;
                    layout(%s, max_vertices = 2) out;
                    layout(location = 0) out vec4 late;
                    layout(location = 1) out vec4 early;
                    %s
                    void main() {
                        early = vec4(1.0);
                        emitAt(gl_in[0].gl_Position);
                        late = vec4(2.0);
                        emitAt(gl_in[1].gl_Position);
                    }
                    """.formatted(layout, helper.formatted(emit));
            for (String source : List.of(direct, called)) {
                for (boolean optimize : new boolean[] {false, true}) {
                    Set<Integer> zeroed = initializedLocations(SpirvLocalInitializer.apply(
                            words(compile(source, optimize, Shaderc.shaderc_glsl_geometry_shader))));
                    String context = emit + (source == called ? " in a helper" : "")
                            + (optimize ? ", optimized" : "") + ": locations " + zeroed;
                    assertTrue(zeroed.contains(0), "late, stored after an emit: " + context);
                    assertTrue(!zeroed.contains(1), "early, stored before every emit: " + context);
                }
            }
        }
    }

    /** The locations of the outputs that carry an initializer; the optimizer strips the names. */
    private static Set<Integer> initializedLocations(int[] module) {
        Map<Integer, Integer> locations = new HashMap<>();
        Set<Integer> result = new HashSet<>();
        for (int at = 5; at < module.length; at += module[at] >>> 16) {
            int opcode = module[at] & 0xFFFF;
            if (opcode == 71 && module[at + 2] == 30) locations.put(module[at + 1], module[at + 3]);
            if (opcode == 59 && module[at + 3] == 3 && module[at] >>> 16 == 5
                    && locations.containsKey(module[at + 2])) {
                result.add(locations.get(module[at + 2]));
            }
        }
        return result;
    }

    @Test
    void unreadableModuleWritesEverything() {
        assertEquals(SpirvOutputWrites.ALL, SpirvOutputWrites.writtenLocations(new int[] {1, 2, 3}));
    }

    /** The output locations SpirvOutputWrites reads from {@code source}, compiled as pack modules are. */
    private static int written(String source) {
        ByteBuffer module = GlslCompiler.compile("outputs.fsh", source, Shaderc.shaderc_glsl_fragment_shader,
                GlslCompiler::optimize);
        try {
            return SpirvOutputWrites.writtenLocations(module);
        } finally {
            MemoryUtil.memFree(module);
        }
    }

    private static ByteBuffer compile(String source) {
        return compile(source, false);
    }

    /** Compiles {@code source}, optimized as pack modules are when {@code optimize} is set. */
    private static ByteBuffer compile(String source, boolean optimize) {
        return compile(source, optimize, Shaderc.shaderc_glsl_fragment_shader);
    }

    /** {@link #compile(String, boolean)} for a stage of shaderc kind {@code kind}. */
    private static ByteBuffer compile(String source, boolean optimize, int kind) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(options,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
        if (optimize) GlslCompiler.optimize(options);
        long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, "locals.glsl", "main", options);
        try {
            assertTrue(result != 0 && Shaderc.shaderc_result_get_compilation_status(result)
                            == Shaderc.shaderc_compilation_status_success,
                    result == 0 ? "no shader result" : Shaderc.shaderc_result_get_error_message(result));
            ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
            ByteBuffer copy = ByteBuffer.allocateDirect(bytes.remaining()).order(ByteOrder.LITTLE_ENDIAN);
            copy.put(bytes).flip();
            return copy;
        } finally {
            if (result != 0) Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    private static int[] words(ByteBuffer bytes) {
        int[] words = new int[bytes.remaining() / 4];
        bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(words);
        return words;
    }
}
