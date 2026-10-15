package net.chimera.gui.options;

import net.minecraft.client.gui.GuiGraphics;

/**
 * The look of the shader pack settings, matching Iris's: flat translucent controls drawn in code as
 * a 1 px border, a 1 px inner ring and a fill, plus the dark panels of tooltips and comments. The
 * layers never overlap, so their alphas blend with the world behind and not with each other.
 */
public final class OptionLook {
    /** A control's look: normal, hovered or focused, or inset (disabled, value boxes, slider tracks). */
    public enum State {
        NORMAL(0x8AE0E0E0, 0x36050505, 0x36232323),
        HOVERED(0xFFFFFFFF, 0x638C8C8C, 0x63A3A3A3),
        INSET(0x8AB3B3B3, 0x73000000, 0x73000000);

        private final int border;
        private final int ring;
        private final int fill;

        State(int border, int ring, int fill) {
            this.border = border;
            this.ring = ring;
            this.fill = fill;
        }
    }

    /** A label whose pending value is not applied yet. */
    static final int MODIFIED = 0xFFFFC94A;
    /** Choice and slider values. */
    static final int VALUE = 0x6688FF;
    static final int WHITE = 0xFFFFFFFF;
    static final int INACTIVE = 0xFFA0A0A0;
    static final int HEADER_LINE = 0x66BEBEBE;
    static final int LIST_BACKGROUND = 0x40000000;
    private static final int PANEL_BORDER = 0xDEDEDEDE;
    private static final int PANEL_FILL = 0xDE000000;

    private OptionLook() {}

    public static void control(GuiGraphics graphics, int x, int y, int width, int height, State state) {
        frame(graphics, x, y, width, height, state.border);
        frame(graphics, x + 1, y + 1, width - 2, height - 2, state.ring);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, state.fill);
    }

    /** The dark panel behind tooltips and comments. */
    static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        frame(graphics, x, y, width, height, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL_FILL);
    }

    /** A 1 px outline whose edges do not overlap at the corners. */
    private static void frame(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        if (width <= 0 || height <= 0) return;
        graphics.fill(x, y, x + width, y + 1, color);
        if (height > 1) graphics.fill(x, y + height - 1, x + width, y + height, color);
        if (height > 2) {
            graphics.fill(x, y + 1, x + 1, y + height - 1, color);
            if (width > 1) graphics.fill(x + width - 1, y + 1, x + width, y + height - 1, color);
        }
    }
}
