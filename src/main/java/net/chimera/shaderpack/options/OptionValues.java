package net.chimera.shaderpack.options;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The values a player has changed from a pack's defaults. Only options the pack declares are kept,
 * and a value equal to the default is not a change. A toggle accepts only {@code true} and
 * {@code false}; anything else resets it. A choice takes any value, even one outside its list,
 * because OptiFine applies option files and profiles unchecked and only the menu keeps to the list.
 */
public final class OptionValues {
    private final OptionSet options;
    private final Map<String, Boolean> toggles = new HashMap<>();
    private final Map<String, String> choices = new HashMap<>();

    private OptionValues(OptionSet options) {
        this.options = options;
    }

    /** The changes {@code stored} makes, by option name; text as an option file holds it. */
    public static OptionValues of(OptionSet options, Map<String, String> stored) {
        OptionValues values = new OptionValues(options);
        values.apply(stored);
        return values;
    }

    public OptionSet options() {
        return options;
    }

    /** Applies more stored text on top: an entry at the default removes that option's change. */
    public void apply(Map<String, String> stored) {
        if (stored == null) return;
        options.toggles().forEach((name, declared) -> {
            String text = stored.get(name);
            if (text == null) return;
            boolean value = text.equals("true") || !text.equals("false") && declared.option().defaultValue();
            if (value == declared.option().defaultValue()) {
                toggles.remove(name);
            } else {
                toggles.put(name, value);
            }
        });
        options.choices().forEach((name, declared) -> {
            String text = stored.get(name);
            if (text == null) return;
            if (text.equals(declared.option().defaultValue())) {
                choices.remove(name);
            } else {
                choices.put(name, text);
            }
        });
    }

    /** The changed toggle value, or empty at the default. */
    public Optional<Boolean> toggle(String name) {
        return Optional.ofNullable(toggles.get(name));
    }

    /** The changed choice value, or empty at the default. */
    public Optional<String> choice(String name) {
        return Optional.ofNullable(choices.get(name));
    }

    /**
     * A toggle's effective value. {@code 0} and {@code 1} read as literals, and a name the pack does
     * not declare reads as on, as in OptiFine's profile and condition checks.
     */
    public boolean toggleOrDefault(String name) {
        if ("0".equals(name)) return false;
        if ("1".equals(name)) return true;
        Boolean changed = toggles.get(name);
        if (changed != null) return changed;
        OptionSet.Declared<ShaderOption.Toggle> declared = options.toggles().get(name);
        return declared == null || declared.option().defaultValue();
    }

    /** A choice's effective value; null for a name the pack does not declare. */
    public String choiceOrDefault(String name) {
        String changed = choices.get(name);
        if (changed != null) return changed;
        OptionSet.Declared<ShaderOption.Choice> declared = options.choices().get(name);
        return declared == null ? null : declared.option().defaultValue();
    }

    /** The effective value of any declared option as option-file text, or null when undeclared. */
    public String effectiveText(String name) {
        if (options.isToggle(name)) return Boolean.toString(toggleOrDefault(name));
        return choiceOrDefault(name);
    }

    public int changedCount() {
        return toggles.size() + choices.size();
    }

    /** Every change as option-file text, sorted by name. */
    public Map<String, String> changes() {
        Map<String, String> result = new TreeMap<>();
        toggles.forEach((name, value) -> result.put(name, Boolean.toString(value)));
        result.putAll(choices);
        return Collections.unmodifiableMap(result);
    }

    public OptionValues copy() {
        OptionValues copy = new OptionValues(options);
        copy.toggles.putAll(toggles);
        copy.choices.putAll(choices);
        return copy;
    }
}
