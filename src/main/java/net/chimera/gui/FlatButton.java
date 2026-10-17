package net.chimera.gui;

import net.chimera.gui.options.OptionLook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** A vanilla button drawn in Iris's flat look instead of the stone sprite, with vanilla's label. */
final class FlatButton extends Button {
    FlatButton(int x, int y, int width, int height, Component message, OnPress onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        OptionLook.State state = !this.active ? OptionLook.State.INSET
                : isHoveredOrFocused() ? OptionLook.State.HOVERED : OptionLook.State.NORMAL;
        OptionLook.control(graphics, getX(), getY(), getWidth(), getHeight(), state);
        renderString(graphics, Minecraft.getInstance().font, getFGColor() | Mth.ceil(this.alpha * 255.0F) << 24);
    }
}
