package net.chimera.gui.options;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A small button in the settings header: Back on a sub-page, then Import, Export and Reset on every
 * page. Reset acts only with Shift held, and greys out until Shift is down or it has focus. Each
 * shows its tooltip at once while hovered or focused, above its top-right corner.
 */
final class HeaderButton extends AbstractWidget {
    static final int HEIGHT = 16;
    static final int ICON_WIDTH = 15;
    private static final int SHADOW = 0xB3000000;
    /** Each glyph row as pairs of first and last lit pixel. */
    private static final int[][] EXPORT_GLYPH = {{3, 3}, {2, 4}, {1, 5}, {3, 3}, {3, 3}, {0, 0, 6, 6}, {0, 0, 6, 6},
            {0, 6}};
    private static final int[][] IMPORT_GLYPH = {{3, 3}, {3, 3}, {1, 5}, {2, 4}, {3, 3}, {0, 0, 6, 6}, {0, 0, 6, 6},
            {0, 6}};
    /** Yellow, orange, pink, violet. */
    private static final int[] EXPORT_GRADIENT = {0xFFFFE066, 0xFFFF9F43, 0xFFFF6B9D, 0xFFB57BFF};
    /** Teal, blue, lavender. */
    private static final int[] IMPORT_GRADIENT = {0xFF3FD9C9, 0xFF4DA6FF, 0xFFB9A7FF};

    enum Kind { BACK, IMPORT, EXPORT, RESET }

    private final OptionContext context;
    private final Kind kind;
    /** Set by the header before each render: the mouse is strictly inside this button. */
    boolean mouseInside;

    HeaderButton(OptionContext context, Kind kind) {
        super(0, 0, width(context, kind), HEIGHT, Component.empty());
        this.context = context;
        this.kind = kind;
    }

    static int width(OptionContext context, Kind kind) {
        return switch (kind) {
            case BACK -> Math.max(42, context.font().width(backLabel()) + 8);
            case RESET -> Math.max(42, context.font().width(Component.translatable("options.chimera.reset")) + 8);
            case IMPORT, EXPORT -> ICON_WIDTH;
        };
    }

    private static Component backLabel() {
        return Component.literal("< ").append(Component.translatable("options.chimera.back")
                .withStyle(ChatFormatting.ITALIC));
    }

    private boolean hot() {
        return mouseInside || isFocused();
    }

    private boolean armed() {
        return OptionCell.shiftDown() || isFocused();
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        boolean inset = kind == Kind.RESET && !armed();
        OptionLook.State state = inset ? OptionLook.State.INSET
                : hot() ? OptionLook.State.HOVERED : OptionLook.State.NORMAL;
        OptionLook.control(graphics, getX(), getY(), getWidth(), HEIGHT, state);
        switch (kind) {
            case BACK -> drawLabel(graphics, backLabel());
            case RESET -> drawLabel(graphics, Component.translatable("options.chimera.reset")
                    .withStyle(inset ? ChatFormatting.GRAY : ChatFormatting.YELLOW));
            case IMPORT, EXPORT -> drawIcon(graphics, getX() + 4, getY() + 4);
        }
        if (hot() && kind != Kind.BACK) {
            Component tooltip = tooltip();
            int width = context.font().width(tooltip);
            context.overlay().tooltip(tooltip, getRight() - (width + 10), getY() - 16);
        }
    }

    private Component tooltip() {
        return switch (kind) {
            case BACK -> Component.empty();
            case IMPORT -> Component.translatable("options.chimera.importSettings.tooltip").withColor(0x4DA6FF);
            case EXPORT -> Component.translatable("options.chimera.exportSettings.tooltip").withColor(0xFC7D3D);
            case RESET -> armed()
                    ? Component.translatable("options.chimera.reset.tooltip").withStyle(ChatFormatting.RED)
                    : Component.translatable("options.chimera.reset.tooltip.holdShift").withStyle(ChatFormatting.GOLD);
        };
    }

    /** A label centred as Iris centres it. */
    private void drawLabel(GuiGraphics graphics, Component label) {
        int width = context.font().width(label);
        graphics.drawString(context.font(), label, getX() + (getWidth() - width) / 2, getY() + 4, OptionLook.WHITE);
    }

    /**
     * A tray with an arrow leaving it (export) or entering it (import), 7 by 8 pixels, white with a
     * shadow, and in a warm (export) or cool (import) gradient while hovered or focused.
     */
    private void drawIcon(GuiGraphics graphics, int x, int y) {
        int[][] rows = kind == Kind.EXPORT ? EXPORT_GLYPH : IMPORT_GLYPH;
        int[] gradient = kind == Kind.EXPORT ? EXPORT_GRADIENT : IMPORT_GRADIENT;
        for (int row = 0; row < rows.length; row++) {
            int color = hot() ? blend(gradient, row / (float) (rows.length - 1)) : OptionLook.WHITE;
            for (int span = 0; span < rows[row].length; span += 2) {
                int from = rows[row][span];
                int to = rows[row][span + 1] + 1;
                graphics.fill(x + from + 1, y + row + 1, x + to + 1, y + row + 2, SHADOW);
                graphics.fill(x + from, y + row, x + to, y + row + 1, color);
            }
        }
    }

    /** The colour {@code t} of the way down a gradient through evenly spaced stops. */
    private static int blend(int[] stops, float t) {
        float position = t * (stops.length - 1);
        int index = Math.min(stops.length - 2, (int) position);
        float local = position - index;
        int from = stops[index];
        int to = stops[index + 1];
        int color = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int a = (from >> shift) & 0xFF;
            int b = (to >> shift) & 0xFF;
            color |= Math.round(a + (b - a) * local) << shift;
        }
        return color;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return event.button() == 0 && activate(event.hasShiftDown());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        return event.isConfirmation() && activate(event.hasShiftDown());
    }

    private boolean activate(boolean shift) {
        if (kind == Kind.RESET && !shift) return false;
        OptionCell.click();
        switch (kind) {
            case BACK -> context.actions().back();
            case IMPORT -> context.actions().importSettings();
            case EXPORT -> context.actions().exportSettings();
            case RESET -> context.actions().reset();
        }
        return true;
    }

    @Override
    public NarratableEntry.NarrationPriority narrationPriority() {
        return NarratableEntry.NarrationPriority.NONE;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}
}
