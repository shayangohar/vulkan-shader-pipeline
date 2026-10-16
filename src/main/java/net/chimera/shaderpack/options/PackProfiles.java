package net.chimera.shaderpack.options;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The pack's {@code profile.<name>} presets. Each entry sets an option ({@code NAME} turns a toggle
 * on, {@code !NAME} off, {@code NAME=value} or {@code NAME:value} sets it), includes another profile
 * ({@code profile.OTHER}), or switches a program off while the profile is current
 * ({@code !program.NAME}).
 *
 * <p>Detecting the current profile tries the profiles that set
 * the most options first, so one that adds a constraint to another wins over it; when none matches
 * the player has a custom set. Cycling steps through that same order, as Iris does.
 */
public final class PackProfiles {
    /** One preset: the option values it sets and the programs it switches off. */
    public record Profile(String name, Map<String, String> values, List<String> disabledPrograms) {
        public Profile {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            disabledPrograms = List.copyOf(disabledPrograms);
        }

        /** True when every option it sets holds that value; options the pack lacks never disagree. */
        public boolean matches(OptionValues current) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String name = entry.getKey();
                if (current.options().isToggle(name)
                        && !Boolean.toString(current.toggleOrDefault(name)).equals(entry.getValue())) {
                    return false;
                }
                if (current.options().isChoice(name) && !entry.getValue().equals(current.choiceOrDefault(name))) {
                    return false;
                }
            }
            return true;
        }
    }

    /** The current profile, if any, and the ones cycling forward and back reach. */
    public record Scan(Optional<Profile> current, Profile next, Profile previous) {}

    private final List<Profile> declared;
    private final List<Profile> byConstraints;
    private final List<String> problems;

    private PackProfiles(List<Profile> declared, List<String> problems) {
        this.declared = List.copyOf(declared);
        List<Profile> sorted = new ArrayList<>(declared);
        sorted.sort(Comparator.comparingInt((Profile profile) -> profile.values().size()).reversed());
        this.byConstraints = List.copyOf(sorted);
        this.problems = List.copyOf(problems);
    }

    public static PackProfiles empty() {
        return new PackProfiles(List.of(), List.of());
    }

    /**
     * Parses {@code profile.*} entries in declaration order. A profile that includes a missing or
     * circular one is left out and reported; OptiFine and Iris refuse the whole pack instead.
     */
    public static PackProfiles parse(Map<String, List<String>> entries, OptionSet options) {
        List<Profile> profiles = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (String name : entries.keySet()) {
            try {
                profiles.add(resolve(name, entries, options, new ArrayDeque<>()));
            } catch (IllegalArgumentException invalid) {
                problems.add("PROFILE_INVALID:" + name + ":" + invalid.getMessage());
            }
        }
        return new PackProfiles(profiles, problems);
    }

    private static Profile resolve(String name, Map<String, List<String>> entries, OptionSet options,
                                   Deque<String> including) {
        List<String> tokens = entries.get(name);
        if (tokens == null) throw new IllegalArgumentException("missing profile " + name);
        if (including.contains(name)) throw new IllegalArgumentException("circular profile " + name);
        including.push(name);
        Map<String, String> values = new LinkedHashMap<>();
        List<String> disabled = new ArrayList<>();
        for (String token : tokens) {
            if (token.startsWith("!program.")) {
                disabled.add(token.substring("!program.".length()));
            } else if (token.startsWith("profile.")) {
                Profile inherited = resolve(token.substring("profile.".length()), entries, options, including);
                values.putAll(inherited.values());
                disabled.addAll(inherited.disabledPrograms());
            } else {
                setting(token, options, values);
            }
        }
        including.pop();
        return new Profile(name, values, disabled);
    }

    private static void setting(String token, OptionSet options, Map<String, String> values) {
        if (token.startsWith("!")) {
            values.put(token.substring(1), "false");
        } else if (token.contains("=")) {
            values.put(token.substring(0, token.indexOf('=')), token.substring(token.indexOf('=') + 1));
        } else if (token.contains(":")) {
            values.put(token.substring(0, token.indexOf(':')), token.substring(token.indexOf(':') + 1));
        } else if (options.isToggle(token)) {
            values.put(token, "true");
        }
    }

    /** The profiles in the order the pack declared them; cycling follows {@link #scan} instead. */
    public List<Profile> declared() {
        return declared;
    }

    public boolean isEmpty() {
        return declared.isEmpty();
    }

    /** Profiles left out, with the reason. */
    public List<String> problems() {
        return problems;
    }

    /**
     * Which profile {@code values} match, tried from the most constrained down; next and previous
     * step through that same order, as the menu's profile button does.
     */
    public Scan scan(OptionValues values) {
        if (byConstraints.isEmpty()) return new Scan(Optional.empty(), null, null);
        for (int index = 0; index < byConstraints.size(); index++) {
            if (byConstraints.get(index).matches(values)) {
                return new Scan(Optional.of(byConstraints.get(index)), step(index, 1), step(index, -1));
            }
        }
        return new Scan(Optional.empty(), byConstraints.getFirst(), byConstraints.getLast());
    }

    private Profile step(int index, int direction) {
        return byConstraints.get(Math.floorMod(index + direction, byConstraints.size()));
    }
}
