package net.chimera.gui;

import net.chimera.ChimeraMod;
import net.chimera.config.ChimeraConfig;
import net.chimera.config.ShaderpackDirectory;
import net.chimera.gui.options.OptionList;
import net.chimera.gui.options.OptionsView;
import net.chimera.render.ChimeraRenderer;
import net.chimera.shaderpack.options.PendingOptions;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The shader pack screen, modelled on Iris's {@code ShaderPackScreen}. It has two modes on one set
 * of bottom buttons (Cancel / Apply / Done, Open Shader Pack Folder, and a switch between them):
 * the pack list, and the settings of the loaded pack ({@link OptionsView}).
 *
 * <p>Applying writes {@code config/chimera.properties} and queues the change through the same safe
 * frame-boundary switch as {@code /chimera pack}. Settings edits wait in {@link PendingOptions}
 * until Apply or Done reloads the pack; Cancel drops them. Tab switches modes, applying first. Esc
 * in the settings goes back a page, or back to the list without applying.
 */
public final class ShaderPackScreen extends Screen {
    private static final Component SELECT_TITLE = Component.translatable("pack.chimera.select.title")
            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    private static final Component CONFIGURE_TITLE = Component.translatable("pack.chimera.configure.title")
            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    private static final int NOTIFICATION_TICKS = 100;
    private static final int LIST_TOP = 36;

    private final @Nullable Screen parent;
    private final Component versionText = Component.literal("Chimera " + ChimeraMod.VERSION)
            .withStyle(ChatFormatting.GRAY);
    private ShaderPackSelectionList packList;
    private Button openFolderButton;
    private Button switchButton;
    private Component notification;
    private int notificationTimer;
    private boolean dropChanges;
    /** What was applied when the screen opened or last applied; Apply acts only on a change. */
    private Choice baseline;
    private boolean optionsMode;
    private OptionsView optionsView;
    private OptionList optionList;

    public ShaderPackScreen(@Nullable Screen parent) {
        super(Component.translatable("options.chimera.shaderPackSelection.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        // Rebuilt on resize and on a mode switch: keep the pending selection and toggle.
        String selected = this.packList == null ? null : this.packList.selectedPack();
        Boolean enabled = this.packList == null ? null : this.packList.shadersEnabled();
        if (this.packList != null) {
            this.packList.close();
            removeWidget(this.packList);
        }

        ChimeraConfig config = ChimeraConfig.get();
        String applied = appliedPackName();
        // The toggle shows what is running; the saved name only preselects a row when nothing is.
        this.packList = new ShaderPackSelectionList(this, this.minecraft, this.width, this.height - 94, LIST_TOP,
                ShaderpackDirectory.root(), enabled != null ? enabled : applied != null,
                applied != null ? applied : config.shaderPack().orElse(null));
        if (selected != null) this.packList.select(selected);
        if (this.baseline == null) this.baseline = currentChoice();
        initModeContent();

        int bottomCenter = this.width / 2 - 50;
        int topCenter = this.width / 2 - 76;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(bottomCenter + 104, this.height - 27, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("options.chimera.apply"), button -> applyChanges())
                .bounds(bottomCenter, this.height - 27, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, button -> dropChangesAndClose())
                .bounds(bottomCenter - 104, this.height - 27, 100, 20).build());
        this.openFolderButton = addRenderableWidget(Button.builder(
                        Component.translatable("options.chimera.openShaderPackFolder"), button -> openFolder())
                .bounds(topCenter - 78, this.height - 51, 152, 20).build());
        this.switchButton = addRenderableWidget(Button.builder(Component.translatable(this.optionsMode
                        ? "options.chimera.shaderPackList" : "options.chimera.shaderPackSettings"),
                        button -> switchMode())
                .bounds(topCenter + 78, this.height - 51, 152, 20).build());
        updateSwitchButton();
    }

    /**
     * The pack list, or the settings of the loaded pack. While a requested pack is still loading the
     * settings stay empty; with no pack coming they give way to the list.
     */
    private void initModeContent() {
        this.optionList = null;
        ChimeraRenderer.LoadedPack pack = settingsPack();
        if (this.optionsMode && pack == null && ChimeraRenderer.selectedPackPath() == null) this.optionsMode = false;
        if (!this.optionsMode) {
            this.optionsView = null;
            addRenderableWidget(this.packList);
            return;
        }
        if (pack == null) return;
        if (this.optionsView == null || !this.optionsView.shows(pack)) {
            this.optionsView = new OptionsView(pack, this.font, this::applyChanges, this::notify,
                    () -> this.minecraft.screen == this);
        }
        this.optionList = addRenderableWidget(this.optionsView.createList(this.minecraft, this.width,
                this.height - 94, LIST_TOP));
    }

    /** The loaded pack, unless a change to another pack (or to none) is still queued. */
    private static ChimeraRenderer.LoadedPack settingsPack() {
        ChimeraRenderer.LoadedPack loaded = ChimeraRenderer.loadedPack();
        return loaded != null && loaded.path().equals(ChimeraRenderer.selectedPackPath()) ? loaded : null;
    }

    /**
     * In the list, settings open only with shaders on and a pack that has settings (or none loaded
     * yet, which Apply will load). In the settings the switch always leads back to the list.
     */
    private void updateSwitchButton() {
        if (this.optionsMode) {
            this.switchButton.active = true;
            return;
        }
        ChimeraRenderer.LoadedPack loaded = ChimeraRenderer.loadedPack();
        this.switchButton.active = this.packList.shadersEnabled()
                && (loaded == null || OptionsView.hasSettings(loaded));
    }

    /** Switches modes, applying first, so the settings always belong to the pack that is loaded. */
    private void switchMode() {
        this.optionsMode = !this.optionsMode;
        applyChanges();
        if (this.optionsMode) {
            Path selected = ChimeraRenderer.selectedPackPath();
            if (selected != null) OptionsView.startAtMainPage(selected);
        }
        rebuildWidgets();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (this.optionList != null) this.optionsView.beginFrame();
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        Component subtitle = this.notification != null && this.notificationTimer > 0 ? this.notification
                : this.optionsMode ? CONFIGURE_TITLE : SELECT_TITLE;
        graphics.drawCenteredString(this.font, subtitle, this.width / 2, 21, 0xFFFFFFFF);
        graphics.drawString(this.font, this.versionText, 2, this.height - 10, 0xFFFFFFFF);
        if (this.optionList != null) this.optionsView.renderOverlay(graphics, this.width, this.height);
    }

    /** The settings keep the world in view for a live preview: no blur behind them. */
    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (this.optionsMode && this.minecraft.level != null) {
            renderMenuBackground(graphics);
        } else {
            super.renderBackground(graphics, mouseX, mouseY, delta);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.notificationTimer > 0) this.notificationTimer--;
        if (this.optionsMode) followLoadedPack();
        if (this.optionsView != null) this.optionsView.tick();
        updateSwitchButton();
    }

    /** Rebuilds the settings when a load finishes, so they show the values the pack now runs with. */
    private void followLoadedPack() {
        ChimeraRenderer.LoadedPack pack = settingsPack();
        boolean stale = pack == null ? this.optionList != null || ChimeraRenderer.selectedPackPath() == null
                : this.optionsView == null || !this.optionsView.shows(pack);
        if (stale) rebuildWidgets();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            if (this.switchButton.active) switchMode();
            return true;
        }
        if (this.optionsMode && event.isEscape()) {
            if (this.optionsView == null || this.optionList == null || !this.optionsView.escape()) {
                this.optionsMode = false;
                rebuildWidgets();
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
        if (this.optionsMode) {
            if (this.optionList != null) this.optionsView.filesDropped(paths);
            return;
        }
        Path root = ShaderpackDirectory.root();
        List<Path> packs = paths.stream().filter(ShaderpackDirectory::isValidPack).toList();
        for (Path pack : packs) {
            String name = pack.getFileName().toString();
            try {
                ShaderpackDirectory.copyInto(root, pack);
            } catch (FileAlreadyExistsException failure) {
                notify(Component.translatable("options.chimera.shaderPackSelection.copyErrorAlreadyExists", name)
                        .withStyle(ChatFormatting.ITALIC, ChatFormatting.RED));
                this.packList.refresh();
                return;
            } catch (IOException failure) {
                ChimeraMod.LOGGER.warn("[chimera] cannot copy dropped shader pack {}", pack, failure);
                notify(Component.translatable("options.chimera.shaderPackSelection.copyError", name)
                        .withStyle(ChatFormatting.ITALIC, ChatFormatting.RED));
                this.packList.refresh();
                return;
            }
        }
        this.packList.refresh();
        notifyAdded(paths, packs);
    }

    private void notifyAdded(List<Path> paths, List<Path> packs) {
        if (packs.isEmpty()) {
            notify((paths.size() == 1
                    ? Component.translatable("options.chimera.shaderPackSelection.failedAddSingle",
                    paths.get(0).getFileName().toString())
                    : Component.translatable("options.chimera.shaderPackSelection.failedAdd"))
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.RED));
        } else if (packs.size() == 1) {
            String name = packs.get(0).getFileName().toString();
            notify(Component.translatable("options.chimera.shaderPackSelection.addedPack", name)
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.YELLOW));
            // Whoever drops one pack in most likely wants it next.
            this.packList.select(name);
        } else {
            notify(Component.translatable("options.chimera.shaderPackSelection.addedPacks", packs.size())
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.YELLOW));
        }
    }

    @Override
    public void onClose() {
        if (!this.dropChanges) applyChanges();
        this.packList.close();
        this.minecraft.setScreen(this.parent);
    }

    /** Any way off the screen (disconnect, another screen) stops watching the folder. */
    @Override
    public void removed() {
        if (this.packList != null) this.packList.close();
        super.removed();
    }

    /** Cancel: the settings edits not applied yet are dropped; what Apply already did stays. */
    private void dropChangesAndClose() {
        this.dropChanges = true;
        PendingOptions.clear();
        onClose();
    }

    /**
     * Applies the pack choice, or else reloads the pack when settings are pending. As in Iris, only
     * a change does anything: opening the screen and pressing Done must not turn off a pack it
     * cannot name (one loaded from outside the shaderpacks folder, for instance).
     */
    private void applyChanges() {
        Choice choice = currentChoice();
        if (!choice.equals(this.baseline)) {
            applySelection(choice);
            return;
        }
        if (PendingOptions.isEmpty() && !PendingOptions.resetRequested()) return;
        if (ChimeraRenderer.reloadPack() == ChimeraRenderer.PackRequestResult.NOT_READY) {
            notify(Component.translatable("options.chimera.shaderPackSelection.notReady")
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.RED));
        }
    }

    /**
     * Saves the selection and switches to it. Shaders off, or nothing selected, means vanilla. A
     * switch to what is already running queues nothing, as the pack command does. Settings pending
     * for one pack are never applied to another.
     */
    private void applySelection(Choice choice) {
        String selected = choice.pack();
        boolean enabled = choice.enabled() && selected != null;
        ChimeraConfig.get().setSelection(selected != null ? selected : ChimeraConfig.get().shaderPack().orElse(null),
                enabled);
        if (!enabled) {
            PendingOptions.clear();
            ChimeraRenderer.disablePack();
            this.packList.setApplied(null);
            this.baseline = choice;
            return;
        }
        Path pack = ShaderpackDirectory.resolve(ShaderpackDirectory.root(), selected).orElse(null);
        if (pack == null) {
            notify(Component.translatable("options.chimera.shaderPackSelection.missing", selected)
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.RED));
            this.packList.refresh();
            return;
        }
        ChimeraRenderer.LoadedPack loaded = ChimeraRenderer.loadedPack();
        if (loaded == null || !loaded.path().toAbsolutePath().normalize().equals(pack.toAbsolutePath().normalize())) {
            PendingOptions.clear();
        }
        ChimeraRenderer.PackRequestResult result = ChimeraRenderer.requestPack(pack);
        if (result == ChimeraRenderer.PackRequestResult.NOT_READY) {
            notify(Component.translatable("options.chimera.shaderPackSelection.notReady")
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.RED));
            return;
        }
        if (result == ChimeraRenderer.PackRequestResult.ALREADY_ACTIVE
                && (!PendingOptions.isEmpty() || PendingOptions.resetRequested())) {
            ChimeraRenderer.reloadPack();
        }
        this.packList.setApplied(selected);
        this.baseline = choice;
    }

    private record Choice(String pack, boolean enabled) {}

    private Choice currentChoice() {
        return new Choice(this.packList.selectedPack(), this.packList.shadersEnabled());
    }

    /** The name of the pack that is running or queued, when it lives in the shaderpacks folder. */
    private static String appliedPackName() {
        Path current = ChimeraRenderer.selectedPackPath();
        if (current == null) return null;
        try {
            Path parent = current.toRealPath().getParent();
            return parent != null && parent.equals(ShaderpackDirectory.root().toRealPath())
                    ? current.getFileName().toString() : null;
        } catch (IOException failure) {
            return null;
        }
    }

    private void openFolder() {
        Path root = ShaderpackDirectory.root();
        CompletableFuture.runAsync(() -> Util.getPlatform().openPath(root));
    }

    private void notify(Component message) {
        this.notification = message;
        this.notificationTimer = NOTIFICATION_TICKS;
    }

    /** Iris moves focus to the bottom row after a pack is picked, so Enter applies it. */
    void focusBottomRow() {
        setFocused(this.openFolderButton);
    }
}
