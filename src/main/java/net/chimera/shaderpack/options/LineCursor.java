package net.chimera.shaderpack.options;

/**
 * Reads one trimmed source line from the front, token by token, with the rules OptiFine and Iris
 * use to recognise option lines. The quirks are deliberate, because packs are written against them:
 * taking whitespace trims the whole remainder, a comment marker swallows every extra slash, and a
 * number is read as a run where each character or the one after it is a digit.
 */
final class LineCursor {
    private String rest;

    LineCursor(String text) {
        this.rest = text;
    }

    /** Consumes {@code token} when the remainder starts with it. */
    boolean take(String token) {
        if (!rest.startsWith(token)) return false;
        rest = rest.substring(token.length());
        return true;
    }

    /** Consumes leading whitespace, and trims the remainder's end too; false when none led. */
    boolean takeWhitespace() {
        if (rest.isEmpty() || !Character.isWhitespace(rest.charAt(0))) return false;
        rest = rest.trim();
        return true;
    }

    /** Consumes a line comment marker: two slashes and any further slashes. */
    boolean takeComment() {
        if (!rest.startsWith("//")) return false;
        int end = 2;
        while (end < rest.length() && rest.charAt(end) == '/') end++;
        rest = rest.substring(end);
        return true;
    }

    boolean contains(String text) {
        return rest.contains(text);
    }

    boolean atEnd() {
        return rest.isEmpty();
    }

    String remainder() {
        return rest;
    }

    /** A run of letters, digits and underscores; null when the remainder does not start with one. */
    String takeWord() {
        int end = 0;
        while (end < rest.length() && isWordCharacter(rest.charAt(end))) end++;
        return end == 0 ? null : split(end);
    }

    /** A number when one parses at the front, otherwise a word. */
    String takeWordOrNumber() {
        String number = takeNumber();
        return number != null ? number : takeWord();
    }

    private String takeNumber() {
        int end = 0;
        while (end < rest.length()) {
            boolean digitHere = Character.isDigit(rest.charAt(end));
            boolean digitNext = end + 1 < rest.length() && Character.isDigit(rest.charAt(end + 1));
            if (end + 1 < rest.length() ? !digitHere && !digitNext : !digitHere) break;
            end++;
        }
        // A float suffix counts only when something follows it.
        if (end > 0 && end + 1 < rest.length() && (rest.charAt(end) == 'f' || rest.charAt(end) == 'F')) end++;
        try {
            Float.parseFloat(rest.substring(0, end));
        } catch (NumberFormatException notANumber) {
            return null;
        }
        return split(end);
    }

    private String split(int end) {
        String token = rest.substring(0, end);
        rest = rest.substring(end);
        return token;
    }

    private static boolean isWordCharacter(char character) {
        return Character.isDigit(character) || Character.isAlphabetic(character) || character == '_';
    }
}
