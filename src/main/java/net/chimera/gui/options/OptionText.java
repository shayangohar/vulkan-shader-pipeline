package net.chimera.gui.options;

import net.chimera.shaderpack.options.PackLanguage;
import net.chimera.shaderpack.options.ShaderOption;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words the settings show for a pack, as Iris finds them: a key in the game's own language
 * first, then in the pack's {@code lang} files (the player's language, then English), else the raw
 * name. Nothing is prettified, and legacy colour codes in a string are left for the font to apply.
 *
 * <p>Iris shows names, values, page titles, profiles and comments as translatable text, so
 * {@code %%} reads as {@code %}; a prefix or suffix is inserted as written.
 */
final class OptionText {
    /** A format specifier as a translatable string reads one: {@code %s}, {@code %1$s}, {@code %%}. */
    private static final Pattern FORMAT = Pattern.compile("%(?:(\\d+)\\$)?([A-Za-z%]|$)");

    private final PackLanguage language;

    OptionText(PackLanguage language) {
        this.language = language;
    }

    /** The text under {@code key} exactly as written. */
    Optional<String> raw(String key) {
        Language game = Language.getInstance();
        if (game.has(key)) return Optional.of(game.getOrDefault(key));
        return language.translate(Minecraft.getInstance().getLanguageManager().getSelected(), key);
    }

    /** The text under {@code key} as a translatable string without arguments shows it. */
    Optional<String> translated(String key) {
        return raw(key).map(OptionText::formatted);
    }

    /**
     * {@code %%} becomes {@code %}. A string asking for an argument cannot be formatted without
     * one, and a translatable string then shows as written, so it is left whole.
     */
    static String formatted(String text) {
        if (text.indexOf('%') < 0) return text;
        Matcher matcher = FORMAT.matcher(text);
        StringBuilder result = new StringBuilder(text.length());
        int last = 0;
        while (matcher.find()) {
            if (matcher.group(1) != null || !"%".equals(matcher.group(2))) return text;
            result.append(text, last, matcher.start()).append('%');
            last = matcher.end();
        }
        return result.append(text, last, text.length()).toString();
    }

    /** An option's name: {@code option.NAME}, else the name as the source spells it. */
    String optionName(String name) {
        return translated("option." + name).orElse(name);
    }

    /**
     * A choice value: the {@code prefix.NAME} text, then {@code value.NAME.VALUE}, or the raw value
     * with {@code suffix.NAME} after it, all in the value colour.
     */
    MutableComponent value(ShaderOption.Choice choice, String value) {
        String name = choice.name();
        String core = translated("value." + name + "." + value)
                .orElseGet(() -> value + raw("suffix." + name).orElse(""));
        return Component.literal(raw("prefix." + name).orElse("") + core).withColor(OptionLook.VALUE);
    }

    String screenName(String id) {
        return translated("screen." + id).orElse(id);
    }

    String profileName(String name) {
        return translated("profile." + name).orElse(name);
    }

    /**
     * A comment's paragraphs: the text without one closing period, split at each period that ends a
     * sentence, the periods themselves dropped.
     */
    static List<String> paragraphs(String comment) {
        String text = comment.endsWith(".") ? comment.substring(0, comment.length() - 1) : comment;
        List<String> paragraphs = new ArrayList<>();
        for (String sentence : text.split("\\. +")) {
            if (!sentence.isBlank()) paragraphs.add(sentence);
        }
        return paragraphs;
    }
}
