package net.chimera.gui.options;

import net.chimera.shaderpack.options.PackProfiles;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.navigation.CommonInputs;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * The pack's profile button. It names the profile the pending values match, or Custom, and steps
 * to the next or previous profile in matching order, queueing every value that profile sets. It has
 * no reset of its own: Reset in the header returns everything to the defaults.
 */
final class ProfileCell extends ValueCell {
    ProfileCell(OptionContext context) {
        super(context);
    }

    @Override
    String label() {
        return Component.translatable("options.chimera.profile").getString();
    }

    @Override
    Component valueText() {
        return context.editor().profile().current()
                .<Component>map(profile -> Component.literal(context.text().profileName(profile.name())))
                .orElseGet(() -> Component.translatable("options.chimera.profile.custom")
                        .withStyle(ChatFormatting.YELLOW));
    }

    @Override
    int minValueWidth() {
        return getWidth() - (context.font().width(label()) + 16);
    }

    @Override
    boolean modified() {
        return false;
    }

    @Override
    void tooltip(boolean labelCut, int mouseX, int mouseY) {}

    @Override
    boolean press(int button) {
        if (button > 1) return false;
        step(button == 0);
        click();
        return true;
    }

    @Override
    boolean key(int key) {
        if (!CommonInputs.selected(key)) return false;
        if (!controlDown()) step(!shiftDown());
        click();
        return true;
    }

    private void step(boolean forward) {
        PackProfiles.Scan scan = context.editor().profile();
        PackProfiles.Profile target = forward ? scan.next() : scan.previous();
        if (target != null) context.editor().applyProfile(target);
    }

    @Override
    Optional<OptionOverlay.Comment> comment() {
        return comment(label(), "profile.comment");
    }
}
