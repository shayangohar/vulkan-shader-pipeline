package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
