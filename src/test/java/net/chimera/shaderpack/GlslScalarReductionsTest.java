package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A scalar any() or all() call gets the overload glslang lacks, as the GL compiler a pack was
 * written against accepts it (Escheridia's any(isnan(occlusion)), audit GD9); a call on a vector,
 * or a reduction the source defines itself, is left alone (GlslScalarReductions).
 */
final class GlslScalarReductionsTest {
    private static final String ANY = "bool any(bool chimeraValue) { return chimeraValue; }";
    private static final String ALL = "bool all(bool chimeraValue) { return chimeraValue; }";

    @Test
    void aScalarReductionGetsItsOverload() {
        assertEquals(List.of(ANY), overloads("float occlusion; void main() { if (any(isnan(occlusion))) return; }"));
        assertEquals(List.of(ALL), overloads("vec4 color; void main() { if (all(isinf(color.r))) return; }"));
        assertEquals(List.of(ANY), overloads("float f; void main() { if (any(isnan(f) || isinf(f))) return; }"));
        assertEquals(List.of(ANY), overloads("bool hit; void main() { if (any(hit)) return; }"));
    }

    @Test
    void aVectorOrDefinedReductionIsLeftAlone() {
        assertEquals(List.of(), overloads("vec3 v; void main() { if (any(isnan(v))) return; }"));
        assertEquals(List.of(), overloads(
                "float f; bool any(bool x) { return x; } void main() { if (any(isnan(f))) return; }"));
    }

    private static List<String> overloads(String source) {
        return GlslScalarReductions.overloads(GlslDeclarations.parseOrNull("#version 120\n" + source));
    }
}
