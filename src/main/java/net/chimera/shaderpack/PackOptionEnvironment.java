package net.chimera.shaderpack;

import net.chimera.shaderpack.options.OptionValues;
import net.chimera.shaderpack.options.ShaderPackOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What a pack's options bring to reading its shaders.properties, as Iris preprocesses that file:
 * every toggle that is on defined as {@code 1}, every choice defined as its value, the toggle names
 * (a program switch reads one that is off as false), and the programs the matching profile switches
 * off. Names the pack does not declare are not macros, whatever its sources define.
 */
record PackOptionEnvironment(Map<String, String> macros, Set<String> toggles, Set<String> disabledPrograms) {
    PackOptionEnvironment {
        macros = Map.copyOf(macros);
        toggles = Set.copyOf(toggles);
        disabledPrograms = Set.copyOf(disabledPrograms);
    }

    static PackOptionEnvironment of(ShaderPackOptions pack, OptionValues values, Set<String> disabledPrograms) {
        Map<String, String> macros = new TreeMap<>();
        pack.options().toggles().keySet().forEach(name -> {
            if (values.toggleOrDefault(name)) macros.put(name, "1");
        });
        pack.options().choices().keySet().forEach(name -> macros.put(name, values.choiceOrDefault(name)));
        return new PackOptionEnvironment(macros, pack.options().toggles().keySet(), disabledPrograms);
    }

    /** The pack's options at their defaults, for a pack read without a staged load. */
    static PackOptionEnvironment defaults(Path shadersDir) {
        if (shadersDir == null || !Files.isDirectory(shadersDir)) {
            return new PackOptionEnvironment(Map.of(), Set.of(), Set.of());
        }
        ShaderPackOptions pack = ShaderPackOptions.load(shadersDir, Map.of());
        return of(pack, pack.values(), Set.copyOf(pack.profileDisabledPrograms(pack.values())));
    }
}
