package net.chimera.gui.options;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * A cell that names something and shows its value in a box on the right: a toggle, a choice, or the
 * profile. The label is cut to leave room for the box, and turns warm yellow while the option has
 * a pending change.
 */
abstract class ValueCell extends OptionCell {
    private static final Component SET_TO_DEFAULT = Component.translatable("options.chimera.setToDefault")
            .withStyle(ChatFormatting.GREEN);

    ValueCell(OptionContext context) {
        super(context);
    }

    /** The name, without the ": " the cell adds. */
    abstract String label();

    abstract Component valueText();

    /** The narrowest the value box may be. */
    abstract int minValueWidth();

    abstract boolean modified();

    /** Draws anything that belongs inside the value box, such as a slider's handle. */
    void decorateValueBox(GuiGraphics graphics, int boxX, int boxWidth) {}

    @Override
    void renderCell(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = getX();
        int y = getY();
        int width = getWidth();
        Component value = valueText();
        int valueWidth = context.font().width(value);
        int box = Math.max(minValueWidth(), valueWidth + 8);
        OptionLook.control(graphics, x, y, width, getHeight(),
                hot() ? OptionLook.State.HOVERED : OptionLook.State.NORMAL);
        OptionLook.control(graphics, x + width - (box + 2), y + 2, box, getHeight() - 4, OptionLook.State.INSET);
        decorateValueBox(graphics, x + width - box, box);
        boolean cut = drawClipped(graphics, label() + ": ", x + 6, y + 7, (width - 8) - box,
                modified() ? OptionLook.MODIFIED : OptionLook.WHITE);
        graphics.drawString(context.font(), value, (x + width - 2) - box / 2 - valueWidth / 2, y + 7, OptionLook.WHITE);
        if (hot()) tooltip(cut, mouseX, mouseY);
    }

    /**
     * Holding Shift offers the reset; otherwise a cut label shows in full, unless the comment is up.
     * The profile cell overrides this with no tooltip at all.
     */
    void tooltip(boolean labelCut, int mouseX, int mouseY) {
        if (shiftDown()) {
            tooltipAtMouse(SET_TO_DEFAULT, mouseX, mouseY);
        } else if (labelCut && !context.overlay().commentShowing()) {
            fullNameTooltip(mouseX, mouseY);
        }
    }

    void fullNameTooltip(int mouseX, int mouseY) {
        tooltipAtMouse(Component.literal(label()), mouseX, mouseY);
    }
}
