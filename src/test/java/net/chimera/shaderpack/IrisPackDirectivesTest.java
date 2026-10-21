package net.chimera.shaderpack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Iris's pack-wide directives (PackDirectives.acceptDirectivesFrom). Chimera read the half-lives
 * from option defaults, dropped drynessHalflife, scanned noiseTextureResolution in every stage, and
 * read directives from programs the pack switches off.
 */
final class IrisPackDirectivesTest {
    @Test
    void drynessHalflifeWritesTheWetnessHalfLifeAsIrisDoes() {
        PackDirectives directives = PackDirectives.read(IrisConstDirectives.find("""
                const float wetnessHalflife = 260.0;
                const float drynessHalflife = 100.0;
                const float eyeBrightnessHalflife = 5.0;
                const int noiseTextureResolution = 512;
                const float ambientOcclusionLevel = 1.5;
                const float centerDepthHalflife = 0.5;
                const int eyeBrightnessHalflife = 3;
                """));
        assertEquals(100.0F, directives.wetnessHalfLife(), "the later drynessHalflife wins");
        assertEquals(200.0F, directives.drynessHalfLife(), "Iris never changes the dryness half-life");
        assertEquals(5.0F, directives.eyeBrightnessHalfLife(), "an int directive of a float name is ignored");
        assertEquals(512, directives.noiseTextureResolution());
        assertEquals(1.0F, directives.ambientOcclusionLevel(), "clamped to [0, 1]");
        assertEquals(0.5F, directives.centerDepthHalfLife());
        assertEquals(PackDirectives.DEFAULTS, PackDirectives.read(List.of()));
    }

    /** Iris reads no source for a program the pack switches off, so it declares nothing. */
    @Test
    void aSwitchedOffProgramDeclaresNothing(@TempDir Path root) throws IOException {
        Path shaders = Files.createDirectories(root.resolve("shaders"));
        Files.writeString(shaders.resolve("shaders.properties"), "program.composite1.enabled=false\n");
        PackConfig.PackConfigData config = PackConfig.parse(List.of(
                new PackProgram("composite", "const int noiseTextureResolution = 128;\n",
                        shaders.resolve("composite.fsh")),
                new PackProgram("composite1", "const int noiseTextureResolution = 64;\n",
                        shaders.resolve("composite1.fsh"))), shaders);
        assertEquals(128, config.directives().noiseTextureResolution());
    }
}
