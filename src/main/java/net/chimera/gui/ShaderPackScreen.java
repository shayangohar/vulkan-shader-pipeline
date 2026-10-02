package net.chimera.gui;

import net.chimera.ChimeraMod;
import net.chimera.config.ChimeraConfig;
import net.chimera.config.ShaderpackDirectory;
import net.chimera.render.ChimeraRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The shader pack selector, modelled on Iris's {@code ShaderPackScreen}: the pack list, Cancel /
 * Apply / Done, Open Shader Pack Folder, and Shader Pack Settings (greyed out until Chimera has a
 * settings page). Applying writes {@code config/chimera.properties} and queues the change through
 * the same safe frame-boundary switch as {@code /chimera pack}.
 */
public final class ShaderPackScreen extends Screen {
    private static final Component SELECT_TITLE = Component.translatable("pack.chimera.select.title")
            .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
    private static final int NOTIFICATION_TICKS = 100;

    private final @Nullable Screen parent;
    private final Component versionText = Component.literal("Chimera " + ChimeraMod.VERSION)
            .withStyle(ChatFormatting.GRAY);
    private ShaderPackSelectionList packList;
    private Button openFolderButton;
    private Component notification;
    private int notificationTimer;
    private boolean dropChanges;
    /** What was applied when the screen opened or last applied; Apply acts only on a change. */
    private Choice baseline;

    public ShaderPackScreen(@Nullable Screen parent) {
        super(Component.translatable("options.chimera.shaderPackSelection.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        // Rebuilt on resize: keep the pending selection and toggle across the rebuild.
        String selected = this.packList == null ? null : this.packList.selectedPack();
        Boolean enabled = this.packList == null ? null : this.packList.shadersEnabled();
        if (this.packList != null) {
            this.packList.close();
            removeWidget(this.packList);
        }

        ChimeraConfig config = ChimeraConfig.get();
        String applied = appliedPackName();
        // The toggle shows what is running; the saved name only preselects a row when nothing is.
        this.packList = new ShaderPackSelectionList(this, this.minecraft, this.width, this.height - 94, 36,
                ShaderpackDirectory.root(), enabled != null ? enabled : applied != null,
                applied != null ? applied : config.shaderPack().orElse(null));
        if (selected != null) this.packList.select(selected);
        if (this.baseline == null) this.baseline = currentChoice();
        addRenderableWidget(this.packList);

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
        Button settings = addRenderableWidget(Button.builder(
                        Component.translatable("options.chimera.shaderPackSettings"), button -> {})
                .bounds(topCenter + 78, this.height - 51, 152, 20).build());
        settings.active = false;
        settings.setTooltip(Tooltip.create(Component.translatable("options.chimera.shaderPackSettings.unavailable")));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFFFF);
        Component subtitle = this.notification != null && this.notificationTimer > 0 ? this.notification : SELECT_TITLE;
        graphics.drawCenteredString(this.font, subtitle, this.width / 2, 21, 0xFFFFFFFF);
        graphics.drawString(this.font, this.versionText, 2, this.height - 10, 0xFFFFFFFF);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.notificationTimer > 0) this.notificationTimer--;
    }

    @Override
    public void onFilesDrop(List<Path> paths) {
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

    private void dropChangesAndClose() {
        this.dropChanges = true;
        onClose();
    }

    /**
     * Saves the selection and switches to it. Shaders off, or nothing selected, means vanilla.
     * A switch to what is already running queues nothing, as the pack command does.
     */
    private void applyChanges() {
        // As Iris: only a change to the pack or the toggle does anything. Opening the screen and
        // pressing Done must not turn off a pack it cannot name (one loaded from outside the
        // shaderpacks folder, for instance).
        Choice choice = currentChoice();
        if (choice.equals(this.baseline)) {
            return;
        }
        String selected = choice.pack();
        boolean enabled = choice.enabled() && selected != null;
        ChimeraConfig.get().setSelection(selected != null ? selected : ChimeraConfig.get().shaderPack().orElse(null),
                enabled);
        if (!enabled) {
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
        ChimeraRenderer.PackRequestResult result = ChimeraRenderer.requestPack(pack);
        if (result == ChimeraRenderer.PackRequestResult.NOT_READY) {
            notify(Component.translatable("options.chimera.shaderPackSelection.notReady")
                    .withStyle(ChatFormatting.ITALIC, ChatFormatting.RED));
            return;
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
