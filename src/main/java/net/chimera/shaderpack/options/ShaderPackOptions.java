package net.chimera.shaderpack.options;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Everything a pack offers the player to configure, with the values currently applied: the
 * discovered options, the menu shaders.properties lays out, the profiles, and the pack's
 * translations. Loading it never fails a pack; what cannot be read is listed in {@link #problems}.
 */
public final class ShaderPackOptions {
    private final OptionDiscovery discovery;
    private final OptionValues values;
    private final PackProfiles profiles;
    private final OptionMenu menu;
    private final PackLanguage language;
    private final List<String> problems;

    private ShaderPackOptions(OptionDiscovery discovery, OptionValues values, PackProfiles profiles,
                              OptionMenu menu, PackLanguage language, List<String> problems) {
        this.discovery = discovery;
        this.values = values;
        this.profiles = profiles;
        this.menu = menu;
        this.language = language;
        this.problems = List.copyOf(problems);
    }

    /**
     * Discovers the options under {@code shadersRoot} and applies {@code stored}, the option file's
     * text values; entries for names the pack does not declare, or at their default, are dropped.
     */
    public static ShaderPackOptions load(Path shadersRoot, Map<String, String> stored) {
        OptionDiscovery discovery = OptionDiscovery.discover(shadersRoot);
        OptionValues values = OptionValues.of(discovery.options(), stored);
        MenuProperties properties = MenuProperties.read(shadersRoot);
        PackProfiles profiles = PackProfiles.parse(properties.profiles(), discovery.options());
        OptionMenu menu = OptionMenu.build(properties, discovery.options(), profiles);
        List<String> problems = new ArrayList<>(discovery.failures());
        discovery.ambiguous().forEach(name -> problems.add("OPTION_AMBIGUOUS:" + name));
        problems.addAll(profiles.problems());
        problems.addAll(properties.problems());
        problems.addAll(menu.problems());
        return new ShaderPackOptions(discovery, values, profiles, menu, PackLanguage.read(shadersRoot), problems);
    }

    public OptionSet options() {
        return discovery.options();
    }

    /** The values in effect; a copy is what a menu edits before applying. */
    public OptionValues values() {
        return values;
    }

    public PackProfiles profiles() {
        return profiles;
    }

    public OptionMenu menu() {
        return menu;
    }

    public PackLanguage language() {
        return language;
    }

    public List<String> problems() {
        return problems;
    }

    /** Which profile the applied values match. */
    public PackProfiles.Scan profile() {
        return profiles.scan(values);
    }

    /** Programs the current profile switches off ({@code !program.NAME}). */
    public List<String> profileDisabledPrograms() {
        return profile().current().map(PackProfiles.Profile::disabledPrograms).orElse(List.of());
    }

    /** The source files the applied values change, with their new text, by pack-relative path. */
    public Map<String, String> editedFiles() {
        return discovery.editedFiles(values);
    }
}
