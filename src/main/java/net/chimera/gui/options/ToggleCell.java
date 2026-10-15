package net.chimera.gui.options;

import net.chimera.shaderpack.options.ShaderOption;
import net.minecraft.ChatFormatting;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * An on/off option. Either mouse button flips it and Shift sets the default. The value is white at
 * the default and green or red away from it, so an Off that the pack chose is not read as a fault.
 */
final class ToggleCell extends ValueCell {
    private final ShaderOption.Toggle option;

    ToggleCell(OptionContext context, ShaderOption.Toggle option) {
        super(context);
        this.option = option;
    }

    private boolean value() {
        return context.editor().pending().toggleOrDefault(option.name());
    }

    @Override
    String label() {
        return context.text().optionName(option.name());
    }

    @Override
    Component valueText() {
        boolean on = value();
        Component text = Component.translatable(on ? "label.chimera.true" : "label.chimera.false");
        if (on == option.defaultValue()) return text;
        return text.copy().withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    @Override
    int minValueWidth() {
        return 28;
    }

    @Override
    boolean modified() {
        return context.editor().modified(option.name());
    }

    @Override
    boolean press(MouseButtonEvent event) {
        if (event.button() > 1) return false;
        if (event.hasShiftDown()) {
            context.editor().reset(option.name());
        } else {
            context.editor().setToggle(option.name(), !value());
        }
        click();
        return true;
    }

    @Override
    boolean key(KeyEvent event) {
        if (!event.isConfirmation()) return false;
        if (event.hasControlDown()) {
            context.editor().reset(option.name());
        } else {
            context.editor().setToggle(option.name(), !value());
        }
        click();
        return true;
    }

    @Override
    Optional<OptionOverlay.Comment> comment() {
        return comment(label(), "option." + option.name() + ".comment");
    }
}
