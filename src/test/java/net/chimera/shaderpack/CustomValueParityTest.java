package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Custom values as Iris builds them from shaders.properties (CustomUniforms, IrisFunctions). */
final class CustomValueParityTest {
    /**
     * Iris preprocesses shaders.properties with the pack's option values as macros
     * (PropertiesPreprocessor), so a custom value reads an option as its value. Reverie's betaRfact
     * reads RAYLEIGH_DENSITY_VARIANCE, which Chimera refused as an unknown name.
     */
    @Test
    void aCustomValueReadsAnOptionAsItsValue(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("composite.fsh"), """
                #version 120
                #define DENSITY 0.25 //[0.25 0.5]
                uniform float density;
                void main() { gl_FragColor = vec4(density); }
                """);
        Files.writeString(shaders.resolve("shaders.properties"), "uniform.float.density = DENSITY * 2.0\n");
        PackRuntimeSettings settings = PackProbe.analyze(root).plan().runtimeSettings();
        assertTrue(settings.rejected().isEmpty(), settings.deviations().toString());
        PackRuntimeSettings.Session session = settings.newSession();
        settings.evaluate((name, component) -> 0.0, session, 0.05f);
        assertEquals(0.5f, session.values()[settings.indexOf("density")], 1.0e-6f);
    }

    /**
     * Iris's function set (IrisFunctions): Alpha Piscium's values call exp2 and twenty corpus
     * values call mix, which Chimera refused. Iris's max and min past two arguments read the second
     * again for each further one, and Iris has no round.
     */
    @Test
    void theFunctionsAreIrisFunctions() {
        double[][] cases = {
                {value("exp2(3.0)"), 8.0}, {value("exp10(2.0)"), 100.0}, {value("log2(8.0)"), 3.0},
                {value("log10(100.0)"), 2.0}, {value("log(2.0, 8.0)"), 3.0}, {value("mix(1.0, 3.0, 0.25)"), 1.5},
                {value("edge(0.5, 0.2) + edge(0.5, 0.5)"), 1.0}, {value("atan2(1.0, 1.0)"), Math.PI / 4.0},
                {value("degrees(radians(90.0)) + sign(-2.0)"), 89.0}, {value("equals(1.0, 1.0)"), 1.0},
                {value("notEquals(1.0, 2.0) + lessThan(1.0, 2.0) + moreThanOrEquals(1.0, 2.0)"), 2.0},
                {value("and(1.0, 0.0) + or(1.0, 0.0) + not(0.0)"), 2.0},
                {value("add(1.0, 2.0) * subtract(5.0, 3.0) / divide(4.0, 2.0) + remainder(5.0, 3.0)"), 5.0},
                {value("negate(2.0) + multiply(2.0, 3.0)"), 4.0}, {value("random(2.0, 2.0)"), 2.0},
                {value("max(1.0, 2.0, 5.0)"), 2.0}, {value("min(3.0, 2.0, 0.0)"), 2.0}};
        for (double[] entry : cases) {
            assertEquals(entry[1], entry[0], 1.0e-9);
        }
        for (String refused : List.of("round(1.5)", "random(1.0)")) {
            assertThrows(PackExpression.Unsupported.class,
                    () -> PackExpression.parse(refused, BiomeIds.constants()), refused);
        }
    }

    private static double value(String expression) {
        PackExpression.Program program = PackExpression.parse(expression, BiomeIds.constants());
        return program.evaluate((name, component) -> 0.0, program.newState(), 0.0f);
    }
}
