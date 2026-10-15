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
    private static final int IMPORT_COLOR = 0xFF4DA6FF;
    private static final int EXPORT_COLOR = 0xFFFC7D3D;

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
            case BACK -> graphics.drawCenteredString(context.font(), backLabel(), getX() + getWidth() / 2, getY() + 4,
                    OptionLook.WHITE);
            case RESET -> graphics.drawCenteredString(context.font(), Component.translatable("options.chimera.reset")
                    .withStyle(inset ? ChatFormatting.GRAY : ChatFormatting.YELLOW), getX() + getWidth() / 2,
                    getY() + 4, OptionLook.WHITE);
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

    /** A tray with an arrow leaving it (export) or entering it (import), 7 by 8 pixels. */
    private void drawIcon(GuiGraphics graphics, int x, int y) {
        int color = hot() ? (kind == Kind.IMPORT ? IMPORT_COLOR : EXPORT_COLOR) : OptionLook.WHITE;
        glyph(graphics, x + 1, y + 1, SHADOW);
        glyph(graphics, x, y, color);
    }

    private void glyph(GuiGraphics graphics, int x, int y, int color) {
        graphics.fill(x, y + 7, x + 7, y + 8, color);
        graphics.fill(x, y + 5, x + 1, y + 7, color);
        graphics.fill(x + 6, y + 5, x + 7, y + 7, color);
        graphics.fill(x + 3, y, x + 4, y + 5, color);
        boolean up = kind == Kind.EXPORT;
        graphics.fill(x + 2, y + (up ? 1 : 3), x + 5, y + (up ? 2 : 4), color);
        graphics.fill(x + 1, y + 2, x + 6, y + 3, color);
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
