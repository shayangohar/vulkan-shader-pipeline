package net.chimera.shaderpack;

import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Pack modules must read zero from locals the authored GLSL never wrote
 * (Complementary parallaxTexDepth), as on the GL drivers packs target.
 * Compiles real GLSL, applies the initializer, and checks every plain-data
 * function variable carries an OpConstantNull of its own type while opaque
 * handles, initialized locals and the id bound stay coherent.
 */
public final class SpirvLocalInitializerHarness {
    private SpirvLocalInitializerHarness() {}

    public static void main(String[] args) {
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
        require(initialized != original, "uninitialized pack locals were left undefined");
        require(initialized[3] > original[3], "new null constants must extend the id bound");
        verifyInitializers(initialized);
        require(SpirvLocalInitializer.apply(initialized) == initialized, "initializer is not idempotent");

        // glslang stores a GLSL initializer after an uninitialized OpVariable,
        // so only a module with no function variables is left byte-identical.
        String noLocals = "#version 460\nlayout(location = 0) out vec4 color;\n"
                + "void main() { color = vec4(0.5); }\n";
        int[] clean = words(compile(noLocals));
        require(SpirvLocalInitializer.apply(clean) == clean, "a module without function variables changed");
        System.out.println("[chimera] pack SPIR-V local initialization: PASS");
        verifyTrigPrecision();
    }

    /**
     * BSL's value-noise hash: the arithmetic feeding sin, including the corner
     * offset that reaches it through a function parameter, carries
     * NoContraction ahead of the types; unrelated arithmetic keeps contraction.
     */
    private static void verifyTrigPrecision() {
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
        require(decorated != original, "the hash arithmetic was left contractible");
        java.util.Set<Integer> arithmetic = new java.util.HashSet<>();
        java.util.Set<Integer> dots = new java.util.HashSet<>();
        java.util.Set<Integer> vectorAdds = new java.util.HashSet<>();
        java.util.Set<Integer> noContraction = new java.util.HashSet<>();
        int vec2Type = -1;
        int floatType = -1;
        int firstType = -1;
        int at = 5;
        for (; at < decorated.length; at += decorated[at] >>> 16) {
            int opcode = decorated[at] & 0xFFFF;
            int count = decorated[at] >>> 16;
            require(count > 0, "malformed instruction at word " + at);
            if (firstType < 0 && opcode >= 19 && opcode <= 39) firstType = at;
            if (opcode == 22) floatType = decorated[at + 1];
            if (opcode == 23 && decorated[at + 2] == floatType && decorated[at + 3] == 2) vec2Type = decorated[at + 1];
            if (opcode == 71 && decorated[at + 2] == 42) {
                require(firstType < 0, "NoContraction decoration follows a type declaration");
                noContraction.add(decorated[at + 1]);
            }
            if (SpirvTrigPrecision.FLOAT_ARITHMETIC.contains(opcode)) arithmetic.add(decorated[at + 2]);
            if (opcode == 148) dots.add(decorated[at + 2]);
            if (opcode == 129 && decorated[at + 1] == vec2Type) vectorAdds.add(decorated[at + 2]);
        }
        require(at == decorated.length, "instruction stream does not end at the module end");
        require(decorated[3] == original[3], "the id bound must not change");
        require(!dots.isEmpty() && noContraction.containsAll(dots), "the hash dot product is contractible");
        require(!vectorAdds.isEmpty() && noContraction.containsAll(vectorAdds),
                "the lattice corner offset reaching the hash through a parameter is contractible");
        require(noContraction.size() < arithmetic.size(), "arithmetic unrelated to sin was decorated");
        System.out.println("[chimera] pack SPIR-V trigonometric precision: PASS");
    }

    private static void verifyInitializers(int[] words) {
        Map<Integer, Integer> pointee = new HashMap<>();
        Map<Integer, Integer> nullType = new HashMap<>();
        int functionVariables = 0;
        int at = 5;
        for (; at < words.length; at += words[at] >>> 16) {
            int opcode = words[at] & 0xFFFF;
            int count = words[at] >>> 16;
            require(count > 0, "malformed instruction at word " + at);
            if (opcode == 46) require(count == 3, "OpConstantNull must be three words");
            if (opcode == 32 && words[at + 2] == 7) pointee.put(words[at + 1], words[at + 3]);
            if (opcode == 46) nullType.put(words[at + 2], words[at + 1]);
            if (opcode == 59 && words[at + 3] == 7) {
                functionVariables++;
                require(count == 5, "function variable %" + words[at + 2] + " has no initializer");
                Integer initializer = words[at + 4];
                Integer type = nullType.get(initializer);
                if (type != null) {
                    require(type.equals(pointee.get(words[at + 1])),
                            "null initializer type differs from variable %" + words[at + 2]);
                }
            }
        }
        require(at == words.length, "instruction stream does not end at the module end");
        require(functionVariables >= 6, "fixture lost its function variables: " + functionVariables);
    }

    private static ByteBuffer compile(String source) {
        long compiler = Shaderc.shaderc_compiler_initialize();
        long options = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(options,
                Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
        long result = Shaderc.shaderc_compile_into_spv(compiler, source,
                Shaderc.shaderc_glsl_fragment_shader, "locals.fsh", "main", options);
        try {
            require(result != 0 && Shaderc.shaderc_result_get_compilation_status(result)
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
