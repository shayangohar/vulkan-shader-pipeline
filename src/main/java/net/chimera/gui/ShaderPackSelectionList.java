package net.chimera.gui;

import net.chimera.ChimeraMod;
import net.chimera.config.ShaderpackDirectory;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;

/**
 * The pack list of {@link ShaderPackScreen}, laid out as Iris's: a shaders on/off row, one row per
 * pack in the shaderpacks folder (the applied one in yellow), and a drag-and-drop hint. The list
 * follows the folder while the screen is open.
 */
public final class ShaderPackSelectionList extends ObjectSelectionList<ShaderPackSelectionList.BaseEntry> {
    private static final Component PACK_LIST_LABEL = Component.translatable("pack.chimera.list.label")
            .withStyle(ChatFormatting.ITALIC, ChatFormatting.GRAY);
    private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
    private static final Identifier BUTTON_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/button_highlighted");
    private static final Identifier BUTTON_DISABLED = Identifier.withDefaultNamespace("widget/button_disabled");
    private static final int APPLIED_COLOR = 0xFFFFF263;
    private static final int DISABLED_COLOR = 0xFFA2A2A2;

    private final ShaderPackScreen screen;
    private final Path root;
    private final TopButtonRowEntry topButtonRow;
    private final WatchService watcher;
    private WatchKey key;
    private String applied;

    ShaderPackSelectionList(ShaderPackScreen screen, Minecraft minecraft, int width, int height, int top,
                            Path root, boolean shadersEnabled, String applied) {
        super(minecraft, width, height, top, 20);
        this.screen = screen;
        this.root = root;
        this.applied = applied;
        this.topButtonRow = new TopButtonRowEntry(shadersEnabled);
        WatchService createdWatcher = null;
        try {
            Files.createDirectories(root);
            createdWatcher = FileSystems.getDefault().newWatchService();
            this.key = root.register(createdWatcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        } catch (IOException failure) {
            ChimeraMod.LOGGER.warn("[chimera] cannot watch {}; the pack list will not refresh", root, failure);
            this.key = null;
        }
        this.watcher = createdWatcher;
        refresh();
    }

    @Override
    public int getRowWidth() {
        return Math.min(308, this.width - 50);
    }

    @Override
    protected int scrollBarX() {
        return this.width - 6;
    }

    @Override
    public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (this.key != null) {
            boolean changed = false;
            for (WatchEvent<?> event : this.key.pollEvents()) {
                if (event.kind() != StandardWatchEventKinds.OVERFLOW) changed = true;
            }
            if (!this.key.reset()) this.key = null;
            if (changed) refresh();
        }
        super.renderWidget(graphics, mouseX, mouseY, delta);
    }

    /** Rebuilds the rows from the folder, keeping the selected pack selected. */
    void refresh() {
        String selected = getSelected() instanceof ShaderPackEntry entry ? entry.packName : this.applied;
        clearEntries();
        List<String> names;
        try {
            names = ShaderpackDirectory.list(this.root);
        } catch (IOException failure) {
            ChimeraMod.LOGGER.error("[chimera] cannot read {}", this.root, failure);
            addEntry(new LabelEntry(Component.translatable("options.chimera.shaderPackSelection.readError")
                    .withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
            return;
        }
        addEntry(this.topButtonRow);
        this.topButtonRow.allowToggle = !names.isEmpty();
        if (names.isEmpty()) {
            addEntry(new PinnedEntry(Component.translatable("options.chimera.downloadShaders"), this::openDownloads));
        }
        for (String name : names) {
            ShaderPackEntry entry = new ShaderPackEntry(name);
            addEntry(entry);
            if (name.equals(selected)) {
                setSelected(entry);
                centerScrollOn(entry);
            }
        }
        addEntry(new LabelEntry(PACK_LIST_LABEL));
    }

    void select(String name) {
        for (BaseEntry entry : children()) {
            if (entry instanceof ShaderPackEntry pack && pack.packName.equals(name)) {
                setSelected(pack);
                return;
            }
        }
    }

    String selectedPack() {
        return getSelected() instanceof ShaderPackEntry entry ? entry.packName : null;
    }

    boolean shadersEnabled() {
        return this.topButtonRow.enabled;
    }

    void setApplied(String name) {
        this.applied = name;
    }

    void close() {
        try {
            if (this.key != null) this.key.cancel();
            if (this.watcher != null) this.watcher.close();
        } catch (IOException failure) {
            ChimeraMod.LOGGER.warn("[chimera] cannot close the shaderpacks watcher", failure);
        }
    }

    private void openDownloads() {
        String url = "https://modrinth.com/shaders";
        this.minecraft.setScreen(new ConfirmLinkScreen(open -> {
            if (open) Util.getPlatform().openUri(url);
            this.minecraft.setScreen(this.screen);
        }, url, true));
    }

    private static void drawButton(GuiGraphics graphics, int x, int y, int width, int height,
                                   boolean hovered, boolean disabled) {
        Identifier sprite = disabled ? BUTTON_DISABLED : hovered ? BUTTON_HIGHLIGHTED : BUTTON;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
    }

    private static void playClick() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    public abstract static class BaseEntry extends ObjectSelectionList.Entry<BaseEntry> {}

    /** A centred caption row. */
    private static final class LabelEntry extends BaseEntry {
        private final Component label;

        LabelEntry(Component label) {
            this.label = label;
        }

        @Override
        public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            graphics.drawCenteredString(Minecraft.getInstance().font, this.label,
                    getContentXMiddle() - 2, getContentY() + (getContentHeight() - 11) / 2, 0xFFC2C2C2);
        }

        @Override
        public Component getNarration() {
            return this.label;
        }
    }

    /** "Shaders: Enabled / Disabled", or "No Packs Present" when the folder is empty. */
    private final class TopButtonRowEntry extends BaseEntry {
        private boolean enabled;
        private boolean allowToggle = true;

        TopButtonRowEntry(boolean enabled) {
            this.enabled = enabled;
        }

        private Component label() {
            return !this.allowToggle
                    ? Component.translatable("options.chimera.shaders.nonePresent").withStyle(ChatFormatting.GRAY)
                    : Component.translatable(this.enabled ? "options.chimera.shaders.enabled"
                    : "options.chimera.shaders.disabled");
        }

        @Override
        public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getContentX();
            int y = getContentY();
            drawButton(graphics, x - 2, y - 2, getContentWidth(), getContentHeight() + 2, hovered, !this.allowToggle);
            graphics.drawCenteredString(Minecraft.getInstance().font, label(), getContentXMiddle() - 2,
                    y + (getContentHeight() - 11) / 2, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return toggle();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            return event.isConfirmation() && toggle();
        }

        private boolean toggle() {
            if (!this.allowToggle) return false;
            this.enabled = !this.enabled;
            playClick();
            return true;
        }

        void enable() {
            this.enabled = true;
        }

        @Override
        public Component getNarration() {
            return label();
        }
    }

    /** A button row, such as "Download Shaders" when the folder is empty. */
    private static final class PinnedEntry extends BaseEntry {
        private final Component label;
        private final Runnable action;

        PinnedEntry(Component label, Runnable action) {
            this.label = label;
            this.action = action;
        }

        @Override
        public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getContentX();
            int y = getContentY();
            drawButton(graphics, x - 2, y - 2, getContentWidth(), getContentHeight() + 2, hovered, false);
            graphics.drawCenteredString(Minecraft.getInstance().font, this.label, getContentXMiddle() - 2,
                    y + (getContentHeight() - 11) / 2, 0xFFFFFFFF);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            playClick();
            this.action.run();
            return true;
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            if (!event.isConfirmation()) return false;
            playClick();
            this.action.run();
            return true;
        }

        @Override
        public Component getNarration() {
            return this.label;
        }
    }

    /** One pack. Picking it selects it, and turns shaders on, as Iris does. */
    final class ShaderPackEntry extends BaseEntry {
        private final String packName;

        ShaderPackEntry(String packName) {
            this.packName = packName;
        }

        @Override
        public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            Font font = Minecraft.getInstance().font;
            int x = getContentX();
            int y = getContentY();
            int width = getContentWidth();
            if (hovered) {
                drawButton(graphics, x - 2, y - 2, width + 4, getContentHeight() + 4, true, false);
            }
            String name = this.packName;
            if (font.width(Component.literal(name).withStyle(ChatFormatting.BOLD)) > getRowWidth() - 3) {
                name = font.plainSubstrByWidth(name, getRowWidth() - 8) + "...";
            }
            MutableComponent text = Component.literal(name);
            if (hovered) text = text.withStyle(ChatFormatting.BOLD);
            boolean enabled = shadersEnabled();
            int color = 0xFFFFFFFF;
            if (enabled && this.packName.equals(applied)) color = APPLIED_COLOR;
            if (!enabled && !hovered) color = DISABLED_COLOR;
            graphics.drawCenteredString(font, text, getContentXMiddle() - 2,
                    y + (getContentHeight() - 11) / 2, color);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return event.button() == 0 && pick();
        }

        @Override
        public boolean keyPressed(KeyEvent event) {
            return event.isConfirmation() && pick();
        }

        private boolean pick() {
            // Iris UX: picking a pack while shaders are off turns them on for the apply.
            topButtonRow.enable();
            setSelected(this);
            screen.focusBottomRow();
            return true;
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.packName);
        }
    }
}
