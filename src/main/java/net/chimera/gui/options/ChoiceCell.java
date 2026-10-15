package net.chimera.gui.options;

import net.chimera.shaderpack.options.ShaderOption;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * An option with a list of values. Left click steps forward through the list and right click back,
 * wrapping at the ends; Shift sets the default. Values show in the value colour, with the pack's
 * prefix, suffix and value names.
 */
class ChoiceCell extends ValueCell {
    protected final ShaderOption.Choice option;

    ChoiceCell(OptionContext context, ShaderOption.Choice option) {
        super(context);
        this.option = option;
    }

    @Override
    String label() {
        return context.text().optionName(option.name());
    }

    @Override
    Component valueText() {
        return context.text().value(option, context.editor().shownChoice(option));
    }

    @Override
    int minValueWidth() {
        return 0;
    }

    @Override
    boolean modified() {
        return context.editor().modified(option.name());
    }

    @Override
    void fullNameTooltip(int mouseX, int mouseY) {
        if (isFocused()) {
            tooltipBeside(Component.literal(label()));
        } else {
            super.fullNameTooltip(mouseX, mouseY);
        }
    }

    @Override
    boolean press(MouseButtonEvent event) {
        if (event.button() > 1) return false;
        if (event.hasShiftDown()) {
            context.editor().reset(option.name());
        } else {
            step(event.button() == 0 ? 1 : -1);
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
            step(event.hasShiftDown() ? -1 : 1);
        }
        click();
        return true;
    }

    private void step(int direction) {
        context.editor().set(option.name(), context.editor().cycle(option, direction));
    }

    @Override
    Optional<OptionOverlay.Comment> comment() {
        return comment(label(), "option." + option.name() + ".comment");
    }
}
