package net.chimera.shaderpack.options;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One configurable shader pack option, as OptiFine and Iris discover it in the pack's sources: a
 * {@code #define} the pack toggles or sets, or one of the recognised {@code const} declarations.
 */
public sealed interface ShaderOption permits ShaderOption.Toggle, ShaderOption.Choice {
    /** Where an option is declared, which also decides how a changed value is written back. */
    enum Kind { DEFINE, CONST }

    String name();

    Kind kind();

    /** The line's trailing comment without the allowed-values list, if it had any text. */
    Optional<String> comment();

    /** The default as the option file stores it: {@code true}/{@code false}, or the value. */
    String defaultText();

    /** An on/off option: a define that is present or commented out, or a {@code const bool}. */
    record Toggle(String name, Kind kind, Optional<String> comment, boolean defaultValue) implements ShaderOption {
        public Toggle {
            comment = cleanComment(comment);
        }

        @Override
        public String defaultText() {
            return Boolean.toString(defaultValue);
        }
    }

    /**
     * An option with a value list: the values in the comment's brackets, in the order the pack wrote
     * them, followed by the default when the list does not name it.
     */
    record Choice(String name, Kind kind, Optional<String> comment, String defaultValue, List<String> values)
            implements ShaderOption {
        public Choice {
            comment = cleanComment(comment);
            values = List.copyOf(values);
        }

        @Override
        public String defaultText() {
            return defaultValue;
        }

        /**
         * The option a value line declares, or empty when its comment has no bracketed list. The list
         * splits on single spaces, so a doubled space yields an empty value, as in OptiFine.
         */
        static Optional<Choice> fromComment(Kind kind, String name, String comment, String defaultValue) {
            if (comment == null) return Optional.empty();
            int open = comment.indexOf('[');
            int close = open < 0 ? -1 : comment.indexOf(']', open);
            if (close < 0) return Optional.empty();
            List<String> values = new ArrayList<>(List.of(comment.substring(open + 1, close).split(" ")));
            if (!values.contains(defaultValue)) values.add(defaultValue);
            String text = (comment.substring(0, open) + comment.substring(close + 1)).trim();
            return Optional.of(new Choice(name, kind, Optional.of(text), defaultValue, values));
        }
    }

    private static Optional<String> cleanComment(Optional<String> comment) {
        return comment == null ? Optional.empty() : comment.filter(text -> !text.isEmpty());
    }
}
