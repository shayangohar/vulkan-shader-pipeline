package net.chimera;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;
import net.neoforged.neoforgespi.language.MavenVersionAdapter;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chimera's mixins into VulkanMod Reforged must all apply (defaultRequire 1), so a Reforged they were
 * not built against crashes the launch at the first missed hook: on 0.3.5, ChimeraRasterOrientationMixin.
 * The manifest bounds the dependency to the tested line, and NeoForge refuses any other host on its
 * dependency screen. The range is read from the manifest and checked with the loader's own parser.
 */
final class HostDependencyTest {
    private static final Path MANIFEST = Path.of("src/main/resources/META-INF/neoforge.mods.toml");

    @Test
    void vulkanModRangeAdmitsOnlyTheTestedReforgedLine() throws IOException {
        VersionRange range = MavenVersionAdapter.createFromVersionSpec(vulkanModRange());
        assertTrue(range.containsVersion(new DefaultArtifactVersion("0.3.0-ALPHA+1.21.1")), range.toString());
        // Later Reforged releases, the separate NeoForge port that shares the mod id, and older Reforged.
        for (String other : List.of("0.3.1-ALPHA+1.21.1", "0.3.5-ALPHA+1.21.1", "0.5.5-dev+6-unstable",
                "0.2.0-ALPHA")) {
            assertFalse(range.containsVersion(new DefaultArtifactVersion(other)), other + " in " + range);
        }
    }

    private static String vulkanModRange() throws IOException {
        CommentedConfig manifest = new TomlParser().parse(Files.readString(MANIFEST));
        List<Config> dependencies = manifest.get("dependencies.chimera");
        return dependencies.stream()
                .filter(dependency -> "vulkanmod".equals(dependency.get("modId")))
                .map(dependency -> dependency.<String>get("versionRange"))
                .findFirst().orElseThrow();
    }
}
