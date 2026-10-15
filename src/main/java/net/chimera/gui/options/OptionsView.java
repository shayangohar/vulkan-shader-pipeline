package net.chimera.gui.options;

import net.chimera.ChimeraMod;
import net.chimera.render.ChimeraRenderer;
import net.chimera.shaderpack.options.OptionEditor;
import net.chimera.shaderpack.options.OptionFile;
import net.chimera.shaderpack.options.PendingOptions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * The settings half of the shader pack screen, for the pack that is loaded: its option list and
 * pages, the header's Back, Import, Export and Reset, and the comment and tooltip overlay. The
 * screen keeps the bottom buttons and Apply; every edit here only queues values for the next load.
 */
public final class OptionsView implements OptionContext.Actions {
    private final ChimeraRenderer.LoadedPack loaded;
    private final OptionContext context;
    private final OptionPages pages;
    private final Runnable apply;
    private final Consumer<Component> notifier;
    private final Consumer<Path> importer;
    private OptionList list;

    /**
     * @param apply    the screen's Apply, which reloads the pack when values are pending
     * @param notifier shows a line under the screen title
     * @param importer imports a chosen file into whichever view the screen shows when the dialog
     *                 closes (a reload may have replaced this one), if the screen is still open
     */
    public OptionsView(ChimeraRenderer.LoadedPack loaded, Font font, Runnable apply, Consumer<Component> notifier,
                       Consumer<Path> importer) {
        this.loaded = loaded;
        OptionEditor editor = new OptionEditor(loaded.options().pack(), loaded.options().applied());
        this.context = new OptionContext(editor, new OptionText(loaded.options().pack().language()), font,
                new OptionOverlay(), new SliderControl(), this);
        this.pages = OptionPages.forPack(loaded.path());
        this.apply = apply;
        this.notifier = notifier;
        this.importer = importer;
    }

    /** True when the pack has anything to configure: its main page is not empty. */
    public static boolean hasSettings(ChimeraRenderer.LoadedPack loaded) {
        return !loaded.options().pack().menu().main().elements().isEmpty();
    }

    /** Starts the pack's settings at its main page, as entering them from the pack list does. */
    public static void startAtMainPage(Path pack) {
        OptionPages.forPack(pack).home();
    }

    /** True while this view shows exactly that load of the pack; a reload brings new values. */
    public boolean shows(ChimeraRenderer.LoadedPack other) {
        return other != null && other.options() == loaded.options();
    }

    /** A fresh list for the open page, scrolled to the top. */
    public OptionList createList(Minecraft minecraft, int width, int height, int top) {
        context.overlay().clear();
        list = new OptionList(minecraft, width, height, top, context, loaded.path().getFileName().toString(), pages);
        return list;
    }

    /** Esc: back one page; false on the main page, where Esc leaves the settings. */
    public boolean escape() {
        if (!pages.back()) return false;
        list.rebuild();
        return true;
    }

    public void tick() {
        context.overlay().tick();
    }

    /** Ends a keyboard slider edit: queued before Apply, Done or a mode switch, dropped for Cancel. */
    public void finishEdits(boolean commit) {
        context.sliders().finishEdits(commit);
    }

    /** A mouse button came up; a dragged slider takes the bucket under the pointer. */
    public void mouseReleased(double mouseX) {
        context.sliders().release((int) mouseX);
    }

    /** Shows the open page from the top again, as Iris does after an Apply that reloads nothing. */
    public void rebuildList() {
        if (list != null) list.rebuild();
    }

    public void beginFrame() {
        context.overlay().beginFrame();
    }

    public void renderOverlay(GuiGraphics graphics, int width, int height) {
        context.overlay().render(graphics, context.font(), width, height);
    }

    /** Files dropped on the settings: exactly one is imported. */
    public void filesDropped(List<Path> paths) {
        if (paths.size() == 1) {
            importFile(paths.getFirst());
        } else if (paths.size() > 1) {
            notifier.accept(Component.translatable("options.chimera.shaderPackOptions.tooManyFiles")
                    .withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
        }
    }

    @Override
    public void openPage(String id) {
        pages.open(id);
        list.rebuild();
    }

    @Override
    public void back() {
        if (pages.back()) list.rebuild();
    }

    /** Every option to its default at once: the next load ignores the option file and the queue. */
    @Override
    public void reset() {
        PendingOptions.clear();
        PendingOptions.requestReset();
        context.editor().refresh();
        apply.run();
    }

    @Override
    public void importSettings() {
        if (refuseFullscreen()) return;
        SettingsTransfer.chooseImport(loaded.path(),
                file -> Minecraft.getInstance().execute(() -> importer.accept(file)));
    }

    @Override
    public void exportSettings() {
        if (!refuseFullscreen()) SettingsTransfer.export(loaded.path());
    }

    /** Native dialogs misbehave over a fullscreen game, so Iris refuses to open them there. */
    private boolean refuseFullscreen() {
        if (!Minecraft.getInstance().getWindow().isFullscreen()) return false;
        notifier.accept(Component.translatable("options.chimera.mustDisableFullscreen")
                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
        return true;
    }

    /** Replaces the pending settings with a file's; they apply with the next Apply. */
    public void importFile(Path file) {
        String name = file.getFileName().toString();
        try {
            context.editor().importValues(OptionFile.readStrict(file));
            notifier.accept(Component.translatable("options.chimera.shaderPackOptions.importedSettings", name)
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
        } catch (IOException failure) {
            ChimeraMod.LOGGER.error("[chimera] cannot import shader settings from {}", file, failure);
            notifier.accept(Component.translatable("options.chimera.shaderPackOptions.failedImport", name)
                    .withStyle(ChatFormatting.RED, ChatFormatting.ITALIC));
        }
    }
}
