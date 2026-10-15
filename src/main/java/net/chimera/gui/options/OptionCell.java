package net.chimera.gui.options;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * One cell of an option row. Its row places it every frame and decides whether the mouse is over
 * it: by column, across the row's full height, as Iris does. A cell with keyboard focus counts as
 * hovered. Mouse presses reach a cell through its row and never focus it; keys reach it once the
 * arrow keys have focused it.
 */
abstract class OptionCell extends AbstractWidget {
    static final int HEIGHT = 22;
    private static final String ELLIPSIS = "...";

    protected final OptionContext context;
    /** Set by the row before each render: the mouse is over this cell's column of the row. */
    boolean rowHovered;

    OptionCell(OptionContext context) {
        super(0, 0, 0, HEIGHT, Component.empty());
        this.context = context;
    }

    /** Hovered by the mouse or focused by the keyboard. */
    final boolean hot() {
        return rowHovered || isFocused();
    }

    @Override
    protected final void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderCell(graphics, mouseX, mouseY);
        if (hot()) context.overlay().hover(this, comment());
    }

    abstract void renderCell(GuiGraphics graphics, int mouseX, int mouseY);

    /** A mouse press the row routed here; true when the cell acted on it. */
    abstract boolean press(MouseButtonEvent event);

    /** A key while the cell has focus; true when the cell acted on it. */
    abstract boolean key(KeyEvent event);

    Optional<OptionOverlay.Comment> comment() {
        return Optional.empty();
    }

    @Override
    public final boolean keyPressed(KeyEvent event) {
        return key(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        return false;
    }

    @Override
    public NarratableEntry.NarrationPriority narrationPriority() {
        return NarratableEntry.NarrationPriority.NONE;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}

    /** The comment the pack writes under {@code key}, titled {@code title}. */
    Optional<OptionOverlay.Comment> comment(String title, String key) {
        return context.text().raw(key).map(body -> new OptionOverlay.Comment(title, body));
    }

    /** Draws {@code text} at (x, y), cut to {@code maxWidth} with "..."; true when it had to be cut. */
    boolean drawClipped(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        var font = context.font();
        boolean cut = font.width(text) > maxWidth;
        String shown = cut ? font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ELLIPSIS))) + ELLIPSIS
                : text;
        graphics.drawString(font, shown, x, y, color);
        return cut;
    }

    /** A tooltip at the mouse, as Iris places them. */
    void tooltipAtMouse(Component text, int mouseX, int mouseY) {
        context.overlay().tooltip(text, mouseX + 2, mouseY - 16);
    }

    /** A tooltip beside the cell, for a cell the keyboard focused. */
    void tooltipBeside(Component text) {
        context.overlay().tooltip(text, getRight() + 2, getY() - 16);
    }

    static boolean shiftDown() {
        return Minecraft.getInstance().hasShiftDown();
    }

    static void click() {
        playButtonClickSound(Minecraft.getInstance().getSoundManager());
    }

    /** A blank cell: padding, {@code <empty>}, or a name the pack does not define. */
    static final class Empty extends OptionCell {
        Empty(OptionContext context) {
            super(context);
            this.active = false;
        }

        @Override
        void renderCell(GuiGraphics graphics, int mouseX, int mouseY) {}

        @Override
        boolean press(MouseButtonEvent event) {
            return false;
        }

        @Override
        boolean key(KeyEvent event) {
            return false;
        }
    }
}
