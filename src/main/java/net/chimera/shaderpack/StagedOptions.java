package net.chimera.shaderpack;

import net.chimera.shaderpack.options.OptionValues;
import net.chimera.shaderpack.options.PackProfiles;
import net.chimera.shaderpack.options.ShaderPackOptions;

import java.util.Map;
import java.util.Set;

/**
 * The options a loaded pack was staged with: everything the pack declares, the values applied to
 * its sources, the JVM overrides it does not declare (left to the preprocessor as locked macros),
 * and the programs the matching profile switches off.
 */
public record StagedOptions(ShaderPackOptions pack, OptionValues applied, Map<String, String> undeclared,
                            Set<String> disabledPrograms) {
    public StagedOptions {
        undeclared = Map.copyOf(undeclared);
        disabledPrograms = Set.copyOf(disabledPrograms);
    }

    /** The profile the applied values match, or {@code Custom} when none does, as Iris names it. */
    public String profileName() {
        return pack.profiles().scan(applied).current().map(PackProfiles.Profile::name).orElse("Custom");
    }

    /** The options of a pack whose sources could not be read. */
    static StagedOptions none() {
        ShaderPackOptions pack = ShaderPackOptions.empty();
        return new StagedOptions(pack, pack.values(), Map.of(), Set.of());
    }
}
