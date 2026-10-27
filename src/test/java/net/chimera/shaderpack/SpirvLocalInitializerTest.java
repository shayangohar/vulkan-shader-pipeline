package net.chimera.shaderpack;

import net.chimera.render.shader.GlslCompiler;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pack modules must read zero from locals the authored GLSL never wrote
 * (Complementary parallaxTexDepth), as on the GL drivers packs target.
 * Compiles real GLSL, applies the initializer, and checks every plain-data
 * function variable carries an OpConstantNull of its own type while opaque
 * handles, initialized locals and the id bound stay coherent.
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
                vec4 look(sampler2D s, vec2 p) { return texture(s, p); }
                void march(inout float depth) { if (depth <= 1.0) depth = look(tex, uv).a; }
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
        verifyInitializers(initialized);
        assertTrue(SpirvLocalInitializer.apply(initialized) == initialized, "initializer is not idempotent");

        // glslang stores a GLSL initializer after an uninitialized OpVariable,
        // so only a module with no function variables is left byte-identical.
        String noLocals = "#version 460\nlayout(location = 0) out vec4 color;\n"
                + "void main() { color = vec4(0.5); }\n";
        int[] clean = words(compile(noLocals));
        assertTrue(SpirvLocalInitializer.apply(clean) == clean, "a module without function variables changed");
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
        assertTrue(initialized[3] == original[3], "undefs become null constants under their own ids");
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

    private static void verifyInitializers(int[] words) {
        Map<Integer, Integer> pointee = new HashMap<>();
        Map<Integer, Integer> nullType = new HashMap<>();
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
                assertTrue(count == 5, "function variable %" + words[at + 2] + " has no initializer");
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

    private static ByteBuffer compile(String source) {
        return compile(source, false);
    }

    /** Compiles {@code source}, optimized as pack modules are when {@code optimize} is set. */
    private static ByteBuffer compile(String source, boolean optimize) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(options,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
        if (optimize) GlslCompiler.optimize(options);
        long result = Shaderc.shaderc_compile_into_spv(compiler, source,
                Shaderc.shaderc_glsl_fragment_shader, "locals.fsh", "main", options);
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
