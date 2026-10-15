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

/**
 * The words the settings show for a pack, as Iris finds them: a key in the game's own language
 * first, then in the pack's {@code lang} files (the player's language, then English), else the raw
 * name. Nothing is prettified, and legacy colour codes in a string are left for the font to apply.
 */
final class OptionText {
    private final PackLanguage language;

    OptionText(PackLanguage language) {
        this.language = language;
    }

    Optional<String> raw(String key) {
        Language game = Language.getInstance();
        if (game.has(key)) return Optional.of(game.getOrDefault(key));
        return language.translate(Minecraft.getInstance().getLanguageManager().getSelected(), key);
    }

    /** An option's name: {@code option.NAME}, else the name as the source spells it. */
    String optionName(String name) {
        return raw("option." + name).orElse(name);
    }

    /**
     * A choice value: the {@code prefix.NAME} text, then {@code value.NAME.VALUE}, or the raw value
     * with {@code suffix.NAME} after it, all in the value colour.
     */
    MutableComponent value(ShaderOption.Choice choice, String value) {
        String name = choice.name();
        String core = raw("value." + name + "." + value)
                .orElseGet(() -> value + raw("suffix." + name).orElse(""));
        return Component.literal(raw("prefix." + name).orElse("") + core).withColor(OptionLook.VALUE);
    }

    String screenName(String id) {
        return raw("screen." + id).orElse(id);
    }

    String profileName(String name) {
        return raw("profile." + name).orElse(name);
    }

    /**
     * A comment's paragraphs: the text without one closing period, split at each period that ends a
     * sentence, the periods themselves dropped.
     */
    static List<String> paragraphs(String comment) {
        String text = comment.endsWith(".") ? comment.substring(0, comment.length() - 1) : comment;
        List<String> paragraphs = new ArrayList<>();
        for (String sentence : text.split("\\. +")) {
            if (!sentence.isEmpty()) paragraphs.add(sentence);
        }
        return paragraphs;
    }
}
