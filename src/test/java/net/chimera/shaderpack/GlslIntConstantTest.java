package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Integral constant expressions of literals, as GL reads an array size. */
final class GlslIntConstantTest {
    private static OptionalInt value(String expression) {
        List<GlslLexer.Token> tokens = GlslLexer.lex(expression);
        return GlslIntConstant.value(tokens, 0, tokens.size());
    }

    @Test
    void readsLiteralsAndIntegerOperatorsWithCPrecedence() {
        assertEquals(OptionalInt.of(8), value("32 / 4"));
        assertEquals(OptionalInt.of(8), value("8u"));
        assertEquals(OptionalInt.of(14), value("2 + 3 * 4"));
        assertEquals(OptionalInt.of(20), value("(2 + 3) * 4"));
        assertEquals(OptionalInt.of(24), value("(1 << 4) + 0x10 - 010"));
        assertEquals(OptionalInt.of(6), value("-3 * -2"));
        assertEquals(OptionalInt.of(7), value("~0 & 7"));
        assertEquals(OptionalInt.of(3), value("7 % 4 | 1 ^ 0"));
    }

    @Test
    void leavesWhatGlDoesNotSizeAnArrayWithOrLeavesUndefined() {
        for (String expression : List.of("N / 4", "1.0", "1e3", "4 / 0", "4 % 0", "1 << 32", "1 << -1",
                "1 < 2", "2147483647 + 1", "(4", "4)", "4 4", "08", "", " ")) {
            assertEquals(OptionalInt.empty(), value(expression), expression);
        }
    }
}
