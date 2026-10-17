package net.chimera.gui.options;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the settings draw over everything else: a cell's comment and tooltips.
 *
 * <p>The comment of the hovered or focused cell appears at the bottom of the screen once that same
 * cell has stayed hovered for more than ten ticks, as in Iris. When the mouse is over one cell and
 * the keyboard focus on another, both report every frame and neither's comment ever shows, which is
 * also how Iris behaves. Tooltips last one frame: every one asked for while rendering is drawn on
 * top at the end of that frame.
 */
final class OptionOverlay {
    /** A comment panel: its title and the pack's text for it. */
    record Comment(String title, String body) {}

    private record Tooltip(Component text, int x, int y) {}

    private static final int COMMENT_DELAY_TICKS = 10;
    private static final int COMMENT_WIDTH = 314;
    private static final int TEXT_WIDTH = 306;

    private Object hovered;
    private Comment hoveredComment;
    private int hoverTicks;
    private Object frameHovered;
    private Comment frameComment;
    private int frameReports;
    private final List<Tooltip> tooltips = new ArrayList<>();

    /** Starts a frame: cells report again whether they are hovered and what they would show. */
    void beginFrame() {
        frameHovered = null;
        frameComment = null;
        frameReports = 0;
        tooltips.clear();
    }

    /** A hovered or focused cell this frame, with its comment if the pack has one. */
    void hover(Object cell, Optional<Comment> comment) {
        frameReports++;
        frameHovered = cell;
        frameComment = comment.orElse(null);
    }

    /** A tooltip panel this frame, its top-left at {@code (x, y)}. */
    void tooltip(Component text, int x, int y) {
        tooltips.add(new Tooltip(text, x, y));
    }

    boolean commentShowing() {
        return hovered != null && hoveredComment != null && hoverTicks > COMMENT_DELAY_TICKS;
    }

    void tick() {
        if (hovered != null) hoverTicks++;
    }

    /** Forgets the hovered cell, as a rebuilt screen does. */
    void clear() {
        hovered = null;
        hoveredComment = null;
        hoverTicks = 0;
    }

    /** Ends the frame: settles which cell is hovered, then draws the comment and the tooltips. */
    void render(GuiGraphics graphics, Font font, int screenWidth, int screenHeight) {
        Object settled = frameReports > 1 ? null : frameHovered;
        if (settled != hovered) {
            hovered = settled;
            hoverTicks = 0;
        }
        hoveredComment = settled == null ? null : frameComment;
        if (commentShowing()) renderComment(graphics, font, screenWidth, screenHeight);
        for (Tooltip tooltip : tooltips) {
            int width = font.width(tooltip.text());
            OptionLook.panel(graphics, tooltip.x(), tooltip.y(), width + 8, 16);
            graphics.drawString(font, tooltip.text(), tooltip.x() + 4, tooltip.y() + 4, OptionLook.WHITE);
        }
    }

    private void renderComment(GuiGraphics graphics, Font font, int screenWidth, int screenHeight) {
        List<FormattedCharSequence> lines = new ArrayList<>();
        for (String paragraph : OptionText.paragraphs(hoveredComment.body())) {
            lines.addAll(font.split(FormattedText.of(paragraph), TEXT_WIDTH));
        }
        int height = Math.max(50, 18 + 10 * lines.size());
        int x = screenWidth / 2 - COMMENT_WIDTH / 2;
        int y = screenHeight - (height + 4);
        OptionLook.panel(graphics, x, y, COMMENT_WIDTH, height);
        graphics.drawString(font, hoveredComment.title(), x + 4, y + 4, OptionLook.WHITE);
        for (int index = 0; index < lines.size(); index++) {
            graphics.drawString(font, lines.get(index), x + 4, y + 16 + 10 * index, OptionLook.WHITE);
        }
    }
}
