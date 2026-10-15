package net.chimera.shaderpack.options;

import java.util.Map;

/**
 * The option values the settings screen shows for a loaded pack, and the edits it makes.
 *
 * <p>Three sets of values meet here: the defaults from the sources, the applied values the pack was
 * built with, and the pending view, which is the applied values with {@link PendingOptions} on top.
 * Every edit goes to the queue; nothing reaches the pack before its next load. An option is
 * modified while its pending value differs from its applied one.
 */
public final class OptionEditor {
    private final ShaderPackOptions pack;
    private final OptionValues applied;
    private OptionValues pending;

    public OptionEditor(ShaderPackOptions pack, OptionValues applied) {
        this.pack = pack;
        this.applied = applied;
        refresh();
    }

    public ShaderPackOptions pack() {
        return pack;
    }

    public OptionValues applied() {
        return applied;
    }

    public OptionValues pending() {
        return pending;
    }

    /** Reads the queue again, after this editor or anything else changed it. */
    public void refresh() {
        pending = applied.copy();
        pending.apply(PendingOptions.view());
    }

    /** True while the queue would change this option at the next load. */
    public boolean modified(String name) {
        String now = applied.effectiveText(name);
        return now != null && !now.equals(pending.effectiveText(name));
    }

    public void set(String name, String text) {
        PendingOptions.put(name, text);
        refresh();
    }

    public void setToggle(String name, boolean value) {
        set(name, Boolean.toString(value));
    }

    /** Queues the option's default, even when it already holds it. */
    public void reset(String name) {
        String text = defaultText(name);
        if (text != null) set(name, text);
    }

    /** The value a choice shows: the pending one, or the applied one while the pending one is off the list. */
    public String shownChoice(ShaderOption.Choice choice) {
        String value = pending.choiceOrDefault(choice.name());
        return choice.values().contains(value) ? value : applied.choiceOrDefault(choice.name());
    }

    /** The position of the pending value in the choice's list; -1 when it is off the list. */
    public int choiceIndex(ShaderOption.Choice choice) {
        return choice.values().indexOf(pending.choiceOrDefault(choice.name()));
    }

    /**
     * The value {@code step} places away from the pending one, wrapping around the list. An
     * off-list value counts from the first entry.
     */
    public String cycle(ShaderOption.Choice choice, int step) {
        int index = Math.max(0, choiceIndex(choice));
        return choice.values().get(Math.floorMod(index + step, choice.values().size()));
    }

    /** The profile the pending values match, with its neighbours. */
    public PackProfiles.Scan profile() {
        return pack.profiles().scan(pending);
    }

    /** Queues every value a profile sets. */
    public void applyProfile(PackProfiles.Profile profile) {
        PendingOptions.putAll(profile.values());
        refresh();
    }

    /**
     * Replaces the settings with an imported file's: every option the pack runs away from its
     * default goes back to it, then the file's values go on top. The result is pending.
     */
    public void importValues(Map<String, String> file) {
        PendingOptions.clear();
        for (String name : applied.changes().keySet()) {
            String text = defaultText(name);
            if (text != null) PendingOptions.put(name, text);
        }
        PendingOptions.putAll(file);
        refresh();
    }

    private String defaultText(String name) {
        OptionSet.Declared<ShaderOption.Toggle> toggle = pack.options().toggles().get(name);
        if (toggle != null) return Boolean.toString(toggle.option().defaultValue());
        OptionSet.Declared<ShaderOption.Choice> choice = pack.options().choices().get(name);
        return choice == null ? null : choice.option().defaultValue();
    }
}
