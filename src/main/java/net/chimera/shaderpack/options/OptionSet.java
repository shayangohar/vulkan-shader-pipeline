package net.chimera.shaderpack.options;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Every option a pack declares, merged across the lines that declare it. Two declarations of one
 * name merge when their defaults agree; when they disagree the name is ambiguous and dropped, as
 * OptiFine and Iris drop it. The maps keep hash order on purpose: a menu's {@code *} entry lists the
 * leftover options in the order Iris's own hash maps iterate them.
 */
public final class OptionSet {
    /** A declaring line: the pack-relative file path and the zero-based line index. */
    public record Location(String path, int line) {}

    /** An option with every line that declares it. */
    public record Declared<T extends ShaderOption>(T option, Set<Location> locations) {
        public Declared {
            locations = Collections.unmodifiableSet(new LinkedHashSet<>(locations));
        }
    }

    private final Map<String, Declared<ShaderOption.Toggle>> toggles;
    private final Map<String, Declared<ShaderOption.Choice>> choices;

    private OptionSet(Map<String, Declared<ShaderOption.Toggle>> toggles,
                      Map<String, Declared<ShaderOption.Choice>> choices) {
        this.toggles = Collections.unmodifiableMap(toggles);
        this.choices = Collections.unmodifiableMap(choices);
    }

    public static OptionSet empty() {
        return new OptionSet(new HashMap<>(), new HashMap<>());
    }

    public Map<String, Declared<ShaderOption.Toggle>> toggles() {
        return toggles;
    }

    public Map<String, Declared<ShaderOption.Choice>> choices() {
        return choices;
    }

    public boolean isToggle(String name) {
        return toggles.containsKey(name);
    }

    public boolean isChoice(String name) {
        return choices.containsKey(name);
    }

    /** Collects declarations; {@link #build} freezes them. */
    static final class Builder {
        private final Map<String, Declared<ShaderOption.Toggle>> toggles = new HashMap<>();
        private final Map<String, Declared<ShaderOption.Choice>> choices = new HashMap<>();
        private final Set<String> ambiguous = new LinkedHashSet<>();

        void add(Location location, ShaderOption option) {
            if (option instanceof ShaderOption.Toggle toggle) {
                merge(toggles, new Declared<>(toggle, Set.of(location)));
            } else {
                merge(choices, new Declared<>((ShaderOption.Choice) option, Set.of(location)));
            }
        }

        /** Names dropped because two declarations disagreed on the default. */
        Set<String> ambiguous() {
            return Collections.unmodifiableSet(ambiguous);
        }

        OptionSet build() {
            return new OptionSet(toggles, choices);
        }

        private <T extends ShaderOption> void merge(Map<String, Declared<T>> target, Declared<T> proposed) {
            String name = proposed.option().name();
            Declared<T> existing = target.get(name);
            if (existing == null) {
                target.put(name, proposed);
                return;
            }
            if (!existing.option().defaultText().equals(proposed.option().defaultText())) {
                // A later declaration that agrees with neither starts the name over, as in Iris.
                target.remove(name);
                ambiguous.add(name);
                return;
            }
            T kept = existing.option().comment().isPresent() ? existing.option() : proposed.option();
            Set<Location> locations = new LinkedHashSet<>(existing.locations());
            locations.addAll(proposed.locations());
            target.put(name, new Declared<>(kept, locations));
        }
    }
}
