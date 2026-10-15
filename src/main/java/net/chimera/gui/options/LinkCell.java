package net.chimera.gui.options;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * A button that opens one of the pack's sub-pages, named by {@code screen.ID}, with an arrow on the
 * right. Iris draws that arrow almost transparent; Chimera draws it white, as it was meant to be.
 */
final class LinkCell extends OptionCell {
    private final String page;

    LinkCell(OptionContext context, String page) {
        super(context);
        this.page = page;
    }

    private String label() {
        return context.text().screenName(page);
    }

    @Override
    void renderCell(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = getX();
        int y = getY();
        int width = getWidth();
        OptionLook.control(graphics, x, y, width, getHeight(),
                hot() ? OptionLook.State.HOVERED : OptionLook.State.NORMAL);
        String label = label();
        String shown = clip(label, width - 9);
        int labelWidth = context.font().width(shown);
        int labelX = (x + width / 2) - labelWidth / 2 - Math.max(labelWidth - (width - 18), 0) / 2;
        graphics.drawString(context.font(), shown, labelX, y + 7, OptionLook.WHITE);
        graphics.drawString(context.font(), ">", x + width - 9, y + 7, OptionLook.WHITE);
        if (hot() && !shown.equals(label)) tooltipAtMouse(Component.literal(label), mouseX, mouseY);
    }

    @Override
    boolean press(MouseButtonEvent event) {
        if (event.button() != 0) return false;
        open();
        return true;
    }

    @Override
    boolean key(KeyEvent event) {
        if (!event.isConfirmation()) return false;
        open();
        return true;
    }

    private void open() {
        click();
        context.actions().openPage(page);
    }

    @Override
    Optional<OptionOverlay.Comment> comment() {
        return comment(label(), "screen." + page + ".comment");
    }
}
