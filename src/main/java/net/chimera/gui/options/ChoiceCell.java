package net.chimera.gui.options;

import net.chimera.shaderpack.options.ShaderOption;
import net.minecraft.client.gui.navigation.CommonInputs;
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

    /** A choice's tooltips sit beside a cell the keyboard focused, as Iris places them. */
    @Override
    void fullNameTooltip(int mouseX, int mouseY) {
        if (isFocused()) {
            tooltipBeside(Component.literal(label()));
        } else {
            super.fullNameTooltip(mouseX, mouseY);
        }
    }

    @Override
    void setToDefaultTooltip(int mouseX, int mouseY) {
        if (isFocused()) {
            tooltipBeside(SET_TO_DEFAULT);
        } else {
            super.setToDefaultTooltip(mouseX, mouseY);
        }
    }

    @Override
    boolean press(int button) {
        if (button > 1) return false;
        if (shiftDown()) {
            context.editor().reset(option.name());
        } else {
            step(button == 0 ? 1 : -1);
        }
        click();
        return true;
    }

    @Override
    boolean key(int key) {
        if (!CommonInputs.selected(key)) return false;
        if (controlDown()) {
            context.editor().reset(option.name());
        } else {
            step(shiftDown() ? -1 : 1);
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
