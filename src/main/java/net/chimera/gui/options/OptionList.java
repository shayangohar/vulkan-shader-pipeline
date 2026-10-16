package net.chimera.gui.options;

import net.chimera.shaderpack.options.OptionMenu;
import net.chimera.shaderpack.options.ShaderOption;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The settings of the loaded pack, laid out as Iris lays them out: a header row with the page title
 * and its buttons, then the page's elements in rows of its column count. Rows are 24 pixels tall,
 * at most 400 wide, over a 25% black background, and the whole list scrolls, header included.
 */
public final class OptionList extends ContainerObjectSelectionList<OptionList.Row> {
    private static final int ROW_HEIGHT = 24;

    private final OptionContext context;
    private final OptionMenu menu;
    private final String packName;
    private final OptionPages pages;

    OptionList(Minecraft minecraft, int width, int height, int top, OptionContext context, String packName,
               OptionPages pages) {
        super(minecraft, width, height, top, ROW_HEIGHT);
        this.context = context;
        this.menu = context.editor().pack().menu();
        this.packName = packName;
        this.pages = pages;
        rebuild();
    }

    /**
     * Shows the open page from the top. A page the pack never defined shows the main page's
     * elements. A slider still in keyboard edit keeps its value; its cell is about to go.
     */
    void rebuild() {
        context.sliders().finishEdits(true);
        clearEntries();
        String page = pages.page();
        Optional<OptionMenu.Screen> sub = page == null ? Optional.empty() : menu.subScreen(page);
        OptionMenu.Screen screen = sub.orElse(menu.main());
        addEntry(new HeaderRow(sub.isPresent() ? page : null));
        List<OptionMenu.Element> elements = screen.elements();
        // Iris fills rows until they hold the column count, so 0 or less gives one cell per row.
        int columns = Math.max(1, screen.columnCount());
        for (int start = 0; start < elements.size(); start += columns) {
            List<OptionCell> cells = new ArrayList<>();
            for (int index = start; index < start + columns; index++) {
                cells.add(index < elements.size() ? cell(elements.get(index)) : new OptionCell.Empty(context));
            }
            addEntry(new CellRow(cells));
        }
        setScrollAmount(0);
    }

    private OptionCell cell(OptionMenu.Element element) {
        return switch (element) {
            case OptionMenu.Empty empty -> new OptionCell.Empty(context);
            case OptionMenu.ProfileButton profile -> new ProfileCell(context);
            case OptionMenu.Link link -> new LinkCell(context, link.screen());
            case OptionMenu.OptionEntry entry -> switch (entry.option()) {
                case ShaderOption.Toggle toggle -> new ToggleCell(context, toggle);
                case ShaderOption.Choice choice -> entry.slider()
                        ? new SliderCell(context, choice) : new ChoiceCell(context, choice);
            };
        };
    }

    @Override
    public int getRowWidth() {
        return Math.min(400, this.width - 12);
    }

    @Override
    protected int scrollBarX() {
        return this.width - 6;
    }

    /** Iris fades the list's separators out in the settings, leaving the world in view. */
    @Override
    protected void renderListSeparators(GuiGraphics graphics) {}

    @Override
    protected void renderListBackground(GuiGraphics graphics) {
        graphics.fill(getX(), getY(), getRight(), getBottom(), OptionLook.LIST_BACKGROUND);
    }

    public abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    /** The page title between Back and the utility buttons, over a thin line. */
    private final class HeaderRow extends Row {
        private final String page;
        private final HeaderButton back;
        private final List<HeaderButton> utilities;
        private final List<HeaderButton> buttons = new ArrayList<>();

        HeaderRow(String page) {
            this.page = page;
            this.back = page == null ? null : new HeaderButton(context, HeaderButton.Kind.BACK);
            this.utilities = List.of(new HeaderButton(context, HeaderButton.Kind.IMPORT),
                    new HeaderButton(context, HeaderButton.Kind.EXPORT),
                    new HeaderButton(context, HeaderButton.Kind.RESET));
            // Keyboard order as Iris's: the utilities, then Back.
            buttons.addAll(utilities);
            if (back != null) buttons.add(back);
        }

        @Override
        public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getX();
            int y = getY();
            int width = getWidth();
            graphics.fill(x - 3, y + 22, x + width, y + 23, OptionLook.HEADER_LINE);
            int utilitiesWidth = utilities.stream().mapToInt(button -> button.getWidth() + 1).sum();
            int utilityX = x + width - 3 - utilitiesWidth;
            for (HeaderButton button : utilities) {
                button.setPosition(utilityX, y);
                utilityX += button.getWidth() + 1;
            }
            if (back != null) back.setPosition(x, y);
            Component title = page == null ? Component.literal(packName).withStyle(ChatFormatting.BOLD)
                    : Component.literal(context.text().screenName(page));
            graphics.textRenderer().acceptScrolling(title, x + width / 2, x + 5, x + width - 10 - utilitiesWidth,
                    y + 5, y + 15);
            for (HeaderButton button : buttons) {
                button.mouseInside = hovered && mouseX > button.getX() && mouseX < button.getRight()
                        && mouseY > button.getY() && mouseY < button.getBottom();
                button.render(graphics, mouseX, mouseY, delta);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return buttons;
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            for (HeaderButton button : buttons) {
                if (button.mouseInside) return button.mouseClicked(event, doubleClick);
            }
            return false;
        }
    }

    /**
     * One row of cells. The row splits its width into equal cells with 2 px gaps, and the cell under
     * the mouse is the one whose share of the row's width the mouse is in, gaps included.
     */
    private final class CellRow extends Row {
        private final List<OptionCell> cells;

        CellRow(List<OptionCell> cells) {
            this.cells = cells;
        }

        private int column(double mouseX) {
            int columns = cells.size();
            int width = getContentWidth();
            double offset = Math.clamp(mouseX - getContentX(), 0.0, width);
            return Math.clamp((int) Math.floor(columns * offset / width), 0, columns - 1);
        }

        @Override
        public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int columns = cells.size();
            float cellWidth = (getContentWidth() - 2 * (columns - 1) - 3) / (float) columns;
            int hoveredColumn = hovered ? column(mouseX) : -1;
            for (int index = 0; index < columns; index++) {
                OptionCell cell = cells.get(index);
                cell.setRectangle((int) cellWidth, OptionCell.HEIGHT,
                        getContentX() + (int) ((cellWidth + 2) * index), getContentY());
                cell.rowHovered = index == hoveredColumn;
                cell.render(graphics, mouseX, mouseY, delta);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return cells;
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            return cells.get(column(event.x())).press(event);
        }
    }
}
