package net.chimera.shaderpack;

import net.chimera.shaderpack.options.OptionFile;
import net.chimera.shaderpack.options.OptionValues;
import net.chimera.shaderpack.options.PackProfiles;
import net.chimera.shaderpack.options.ShaderPackOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * The options a loaded pack was staged with: everything the pack declares with the stored values
 * (the option file and the settings screen's pending changes), the values applied to its sources
 * (stored values with JVM overrides on top), the JVM overrides it does not declare (left to the
 * preprocessor as locked macros), and the programs the matching profile switches off.
 */
public record StagedOptions(ShaderPackOptions pack, OptionValues applied, Map<String, String> undeclared,
                            Set<String> disabledPrograms, boolean optionFileReadable) {
    public StagedOptions {
        undeclared = Map.copyOf(undeclared);
        disabledPrograms = Set.copyOf(disabledPrograms);
    }

    /** The applied values as shaders.properties sees them. */
    PackOptionEnvironment environment() {
        return PackOptionEnvironment.of(pack, applied, disabledPrograms);
    }

    /** The profile the applied values match, or {@code Custom} when none does, as Iris names it. */
    public String profileName() {
        return pack.profiles().scan(applied).current().map(PackProfiles.Profile::name).orElse("Custom");
    }

    /**
     * Brings {@code <pack>.txt} in line with the stored values, as Iris rewrites it after every load:
     * the non-default values only, and no file when there are none. JVM overrides are never written.
     * A pack that declares no options (one whose sources could not be read), or an option file that
     * could not be parsed, leaves the file alone.
     */
    public void saveOptionFile(Path packPath) throws IOException {
        if (!optionFileReadable || pack.options().toggles().isEmpty() && pack.options().choices().isEmpty()) return;
        Path file = OptionFile.of(packPath);
        Map<String, String> stored = pack.values().changes();
        boolean current = stored.equals(OptionFile.read(file)) && Files.exists(file) != stored.isEmpty();
        if (!current) OptionFile.write(file, pack.values());
    }

    /** The options of a pack whose sources could not be read. */
    static StagedOptions none() {
        ShaderPackOptions pack = ShaderPackOptions.empty();
        return new StagedOptions(pack, pack.values(), Map.of(), Set.of(), true);
    }
}
