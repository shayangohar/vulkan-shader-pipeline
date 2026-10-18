package net.chimera.gui.options;

import net.chimera.shaderpack.options.ShaderOption;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * A choice the pack lists under {@code sliders}. At rest it looks like a choice with a small handle
 * in its value box; hovered or focused it becomes a track with the value centred and the name in a
 * tooltip. Dragging splits the track into one equal bucket per value.
 *
 * <p>A drag follows the pointer and commits on the mouse release, or as soon as the pointer leaves
 * the cell, after the last position is taken. The arrow keys edit the value after Enter, and
 * leaving that edit (Enter again, a page change, Apply or Done) commits it; Iris often drops those
 * keyboard edits. Cancel ends an edit without committing it.
 */
final class SliderCell extends ChoiceCell {
    private boolean dragging;
    private boolean editing;
    private int working = -1;

    SliderCell(OptionContext context, ShaderOption.Choice option) {
        super(context, option);
    }

    @Override
    int minValueWidth() {
        return 35;
    }

    private int index() {
        return dragging || editing ? working : context.editor().choiceIndex(option);
    }

    @Override
    Component valueText() {
        if (!(dragging || editing) || working < 0) return super.valueText();
        return context.text().value(option, option.values().get(working));
    }

    @Override
    void renderCell(GuiGraphics graphics, int mouseX, int mouseY) {
        if (dragging) {
            follow(mouseX);
            if (!rowHovered) {
                dragging = false;
                context.sliders().dragging(null);
                commit();
            }
        }
        if (hot()) {
            renderActive(graphics, mouseX, mouseY);
        } else {
            super.renderCell(graphics, mouseX, mouseY);
        }
    }

    /** The resting look's handle, inside the value box. */
    @Override
    void decorateValueBox(GuiGraphics graphics, int boxX, int boxWidth) {
        int count = option.values().size();
        int index = index();
        if (index < 0 || count < 2) return;
        int x = boxX + (int) (index / (float) (count - 1) * ((boxWidth - 4) - 4));
        OptionLook.control(graphics, x, getY() + 4, 4, getHeight() - 8, OptionLook.State.NORMAL);
    }

    private void renderActive(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = getX();
        int y = getY();
        int width = getWidth();
        OptionLook.control(graphics, x, y, width, getHeight(),
                isFocused() ? OptionLook.State.HOVERED : OptionLook.State.NORMAL);
        OptionLook.control(graphics, x + 2, y + 2, width - 4, getHeight() - 4, OptionLook.State.INSET);
        int count = option.values().size();
        int index = index();
        if (index >= 0 && count > 1) {
            int handleX = x + 4 + (int) (index / (float) (count - 1) * ((width - 8) - 6));
            OptionLook.control(graphics, handleX, y + 4, 6, getHeight() - 8,
                    dragging || editing ? OptionLook.State.HOVERED : OptionLook.State.NORMAL);
        }
        Component value = valueText();
        graphics.drawString(context.font(), value, (x + width / 2) - context.font().width(value) / 2, y + 7,
                OptionLook.WHITE);
        if (shiftDown()) {
            setToDefaultTooltip(mouseX, mouseY);
        } else if (!context.overlay().commentShowing()) {
            fullNameTooltip(mouseX, mouseY);
        }
    }

    /** The bucket under the pointer: the track split into one equal part per value. */
    private void follow(int mouseX) {
        int count = option.values().size();
        float fraction = Math.clamp((mouseX - (getX() + 4)) / (float) (getWidth() - 8), 0.0F, 1.0F);
        working = Math.min(count - 1, (int) (fraction * count));
    }

    private void commit() {
        if (working >= 0) context.editor().set(option.name(), option.values().get(working));
        click();
    }

    /** The mouse button came up while this slider was dragged. */
    void release(int mouseX) {
        if (!dragging) return;
        follow(mouseX);
        dragging = false;
        commit();
    }

    /** The drag ends where the slider shows it, without a release. */
    void endDrag() {
        if (!dragging) return;
        dragging = false;
        commit();
    }

    /** Leaves keyboard editing, queueing the edited value or dropping it. */
    void endEdit(boolean commitValue) {
        if (!editing) return;
        editing = false;
        if (commitValue) commit();
    }

    @Override
    boolean press(int button) {
        if (button != 0) return false;
        if (shiftDown()) {
            context.editor().reset(option.name());
        } else {
            dragging = true;
            working = Math.max(0, context.editor().choiceIndex(option));
            context.sliders().dragging(this);
        }
        click();
        return true;
    }

    @Override
    boolean key(int key) {
        if (CommonInputs.selected(key)) {
            confirm(shiftDown());
            return true;
        }
        if (!editing || !(key == GLFW.GLFW_KEY_LEFT || key == GLFW.GLFW_KEY_RIGHT)) return false;
        working = Math.clamp(working + (key == GLFW.GLFW_KEY_RIGHT ? 1 : -1), 0, option.values().size() - 1);
        return true;
    }

    /** Enter starts or ends keyboard editing; Shift+Enter resets and keeps editing, as Iris does. */
    private void confirm(boolean shift) {
        if (shift) {
            context.editor().reset(option.name());
            if (editing) working = Math.max(0, option.values().indexOf(option.defaultValue()));
            click();
        } else if (editing) {
            context.sliders().finishEdits(true);
        } else {
            editing = true;
            working = Math.max(0, context.editor().choiceIndex(option));
            context.sliders().editing(this);
            click();
        }
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (!focused && editing) context.sliders().finishEdits(true);
    }
}
