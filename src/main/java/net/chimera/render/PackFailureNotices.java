package net.chimera.render;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What a pack lost, told once per distinct failure (TASK-922), as Iris tells a failed load: a chat
 * line that names the pack and the program or pass, says whether Chimera skipped it or the pack fell
 * back to vanilla, and ends with Copy Info. A line found before a player is in a world waits for one,
 * as Iris's does (MixinClientPacketListener.handleLogin). The current pack's failures also feed
 * /chimera pack status.
 *
 * <p>A failure is recorded where it happens, on any thread, and published once the load or frame
 * that found it has its report: publishing queues its line, and the client tick posts it.
 */
public final class PackFailureNotices {
    /** What a failure did to the frame. */
    public enum Outcome {
        /** Chimera skipped one program or pass; the rest of the pack runs. */
        SKIPPED,
        /** The whole pack fell back to vanilla. */
        VANILLA,
        /** The pack did not load; what ran before it, or vanilla, runs. */
        LOAD_FAILED
    }

    /**
     * One failure: the pack, the program or pass it struck, the phase it struck in, what it did, a
     * one-line reason, the text Copy Info copies, and the exception behind it, if one was thrown.
     */
    public record Failure(String pack, String subject, String phase, Outcome outcome, String reason, String info,
                          Throwable cause) {
        public Failure {
            pack = pack == null ? "?" : pack;
            subject = subject == null ? "pack" : subject;
            reason = oneLine(reason);
            info = info == null ? "" : info;
        }

        public Failure(String pack, String subject, String phase, Outcome outcome, String reason, String info) {
            this(pack, subject, phase, outcome, reason, info, null);
        }

        /** One program's skip, or one fallback, whatever phase found it first. */
        String key() {
            return pack + '\n' + subject + '\n' + outcome;
        }
    }

    /**
     * One chat line: its text, whether it says the pack stopped (red) or not (yellow), and the text
     * its Copy Info copies, or null for a line without one.
     */
    record Line(String text, boolean severe, String info) {}

    /** One publish posts this many lines; the rest fold into one that points at the status command. */
    static final int LINES_PER_PUBLISH = 3;
    private static final int REASON_LENGTH = 160;

    private static final Object LOCK = new Object();
    /** Recorded and not yet published. */
    private static final List<Failure> pending = new ArrayList<>();
    /** Published this game: a failure is told once. */
    private static final Set<String> announced = new HashSet<>();
    /** The current pack's failures, for the status command. */
    private static final List<Failure> current = new ArrayList<>();
    /** Lines waiting for a player in a world. */
    private static final List<Line> held = new ArrayList<>();
    private static String currentPack;
    private static Path currentShadersDir;

    private PackFailureNotices() {}

    /**
     * A load of {@code pack} begins, from {@code shadersDir}: the status command lists this load's
     * failures from now on. A failure told before is not told again.
     */
    public static void beginLoad(String pack, Path shadersDir) {
        synchronized (LOCK) {
            currentPack = pack;
            currentShadersDir = shadersDir;
            current.clear();
        }
    }

    /** The pack being loaded or run, which failures found without one are recorded against. */
    public static String currentPack() {
        synchronized (LOCK) {
            return currentPack;
        }
    }

    /** Where the current pack's shader sources are, for naming an authored line; null without a pack. */
    public static Path currentShadersDir() {
        synchronized (LOCK) {
            return currentShadersDir;
        }
    }

    /** Records a failure; the next publish tells it unless it was told before. */
    public static void record(Failure failure) {
        synchronized (LOCK) {
            String key = failure.key();
            if (current.stream().noneMatch(known -> known.key().equals(key))) current.add(failure);
            if (!announced.contains(key) && pending.stream().noneMatch(known -> known.key().equals(key))) {
                pending.add(failure);
            }
        }
    }

    /**
     * Queues a line for each failure recorded since the last publish: at most
     * {@link #LINES_PER_PUBLISH}, the rest folded into one line.
     */
    public static void publish() {
        List<Failure> told;
        synchronized (LOCK) {
            if (pending.isEmpty()) return;
            told = List.copyOf(pending);
            pending.clear();
            told.forEach(failure -> announced.add(failure.key()));
        }
        List<Line> lines = new ArrayList<>();
        int shown = told.size() <= LINES_PER_PUBLISH ? told.size() : LINES_PER_PUBLISH - 1;
        for (int index = 0; index < shown; index++) lines.add(line(told.get(index)));
        if (shown < told.size()) {
            lines.add(new Line("[Chimera] " + told.get(shown).pack() + ": " + (told.size() - shown)
                    + " more failures; /chimera pack status lists them.", false, null));
        }
        synchronized (LOCK) {
            held.addAll(lines);
        }
    }

    /** Posts the waiting lines once a player is in a world; the client tick calls it. */
    public static void flush() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.gui == null) return;
        List<Line> lines;
        synchronized (LOCK) {
            if (held.isEmpty()) return;
            lines = List.copyOf(held);
            held.clear();
        }
        lines.forEach(line -> minecraft.gui.getChat().addMessage(component(line)));
    }

    /** The current pack's failures, oldest first. */
    public static List<Failure> current() {
        synchronized (LOCK) {
            return List.copyOf(current);
        }
    }

    /** The status command's lines for the current pack's failures; empty without any. */
    public static List<String> statusLines() {
        List<String> lines = new ArrayList<>();
        for (Failure failure : current()) {
            lines.add("  " + failure.subject() + ": " + describe(failure.outcome()) + " ("
                    + failure.phase() + ": " + failure.reason() + ")");
        }
        return lines;
    }

    /** One failure's chat line. */
    static Line line(Failure failure) {
        String text = switch (failure.outcome()) {
            case SKIPPED -> "[Chimera] " + failure.pack() + ": Chimera skipped " + failure.subject() + " ("
                    + failure.reason() + "); the rest of the pack runs. ";
            case VANILLA -> "[Chimera] " + failure.pack() + " fell back to vanilla"
                    + (failure.subject().equals("pack") ? "" : " after " + failure.subject() + " failed") + ": "
                    + failure.reason() + " ";
            case LOAD_FAILED -> "[Chimera] Failed to load " + failure.pack()
                    + (failure.subject().equals("pack") ? "" : " (" + failure.subject() + ")") + ": "
                    + failure.reason() + " ";
        };
        return new Line(text, failure.outcome() != Outcome.SKIPPED, failure.info());
    }

    /**
     * A line as chat shows it, ending with Iris's Copy Info link: underlined and blue, it copies the
     * line's info, and hovering says so. Built on the client thread, where the chat classes are ready.
     */
    static Component component(Line line) {
        MutableComponent text = Component.literal(line.text())
                .withStyle(line.severe() ? ChatFormatting.RED : ChatFormatting.YELLOW);
        if (line.info() == null) return text;
        return text.append(Component.literal("[Copy Info]").withStyle(Style.EMPTY
                .withColor(ChatFormatting.BLUE)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, line.info()))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Click to copy to clipboard")))));
    }

    private static String describe(Outcome outcome) {
        return switch (outcome) {
            case SKIPPED -> "skipped";
            case VANILLA -> "pack fell back to vanilla";
            case LOAD_FAILED -> "pack failed to load";
        };
    }

    /** The first line of {@code reason}, cut to a chat line's length. */
    static String oneLine(String reason) {
        return shortLine(reason, REASON_LENGTH);
    }

    /** The first line of {@code text}, cut to {@code length} characters and a mark that it was cut. */
    public static String shortLine(String text, int length) {
        String line = text == null || text.isBlank() ? "unknown" : text.strip().lines().findFirst().orElse("");
        return line.length() <= length ? line : line.substring(0, length) + "...";
    }

    /** Forgets everything; tests only. */
    static void reset() {
        synchronized (LOCK) {
            pending.clear();
            announced.clear();
            current.clear();
            held.clear();
            currentPack = null;
            currentShadersDir = null;
        }
    }

    /** The lines waiting for a player; tests only. */
    static List<Line> held() {
        synchronized (LOCK) {
            return List.copyOf(held);
        }
    }
}
