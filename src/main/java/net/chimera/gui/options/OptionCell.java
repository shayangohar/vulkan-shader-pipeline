package net.chimera.gui.options;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

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
    abstract boolean press(int button);

    /** A key while the cell has focus; true when the cell acted on it. */
    abstract boolean key(int key);

    Optional<OptionOverlay.Comment> comment() {
        return Optional.empty();
    }

    @Override
    public final boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return key(keyCode);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    @Override
    public NarratableEntry.NarrationPriority narrationPriority() {
        return NarratableEntry.NarrationPriority.NONE;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {}

    /** The comment the pack writes under {@code key}, titled {@code title}; none when it has no text. */
    Optional<OptionOverlay.Comment> comment(String title, String key) {
        return context.text().translated(key).filter(body -> !OptionText.paragraphs(body).isEmpty())
                .map(body -> new OptionOverlay.Comment(title, body));
    }

    /** Draws {@code text} at (x, y), cut to {@code maxWidth} with "..."; true when it had to be cut. */
    boolean drawClipped(GuiGraphics graphics, String text, int x, int y, int maxWidth, int color) {
        String shown = clip(text, maxWidth);
        graphics.drawString(context.font(), shown, x, y, color);
        return !shown.equals(text);
    }

    /** {@code text}, or its longest start that fits {@code maxWidth} with "..." added. */
    String clip(String text, int maxWidth) {
        var font = context.font();
        if (font.width(text) <= maxWidth) return text;
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(ELLIPSIS))) + ELLIPSIS;
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
        return Screen.hasShiftDown();
    }

    static boolean controlDown() {
        return Screen.hasControlDown();
    }

    static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
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
        boolean press(int button) {
            return false;
        }

        @Override
        boolean key(int key) {
            return false;
        }
    }
}
