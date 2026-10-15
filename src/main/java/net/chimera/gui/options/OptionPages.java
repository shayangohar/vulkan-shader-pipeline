package net.chimera.gui.options;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Which settings page is open, with the pages before it. One history lives per loaded pack and
 * outlasts the screen, so Apply, a reload or a resize keeps the page; a different pack starts over
 * at its main page.
 */
final class OptionPages {
    private static OptionPages current;

    private final Path pack;
    private final Deque<String> history = new ArrayDeque<>();

    private OptionPages(Path pack) {
        this.pack = pack;
    }

    static OptionPages forPack(Path pack) {
        if (current == null || !current.pack.equals(pack)) current = new OptionPages(pack);
        return current;
    }

    /** The open page's id; null for the main page. */
    String page() {
        return history.peek();
    }

    void open(String id) {
        history.push(id);
    }

    /** Returns to the previous page; false on the main page. */
    boolean back() {
        if (history.isEmpty()) return false;
        history.pop();
        return true;
    }

    /** Back to the main page, as entering the settings from the pack list does. */
    void home() {
        history.clear();
    }
}
