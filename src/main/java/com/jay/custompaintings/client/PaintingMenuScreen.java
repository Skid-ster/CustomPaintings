package com.jay.custompaintings.client;

import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.RecipeCatalog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/**
 * The Painting Menu: browse every painting in the library and put it straight into your hotbar.
 *
 * <p>Opened with a keybind (default {@code P}), the {@code /paintingmenu} command, or a button in
 * the library screen. It works identically in Survival and Creative, which is what replaces
 * crafting once the finite wood/dye recipe catalogue is exhausted.</p>
 */
public final class PaintingMenuScreen extends Screen {
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFAAB2BD;
    private static final int SUCCESS = 0xFF9CCC65;
    private static final int WARNING = 0xFFFFD166;
    private static final int ERROR = 0xFFFF8080;

    private final Screen parent;
    private final ClientPaintingLibrary library;
    private EditBox search;
    private PaintingList list;
    private Button takeButton;
    private Button takeStackButton;
    private String query = "";
    private double scroll;
    private int selectedId = -1;
    private Component notice;
    private int noticeColor = MUTED;
    private long displayedGeneration;

    public PaintingMenuScreen(Screen parent) {
        super(Component.translatable("menu.custompaintings.title"));
        this.parent = parent;
        this.library = CustomPaintingsClient.library();
    }

    @Override
    protected void init() {
        displayedGeneration = CustomPaintingsClient.paintings().generation();
        int margin = this.width < 420 ? 10 : 24;
        int contentWidth = Math.max(140, this.width - margin * 2);
        int left = (this.width - contentWidth) / 2;

        search = new EditBox(this.font, left, 42, contentWidth, 20,
                Component.translatable("menu.custompaintings.search"));
        search.setHint(Component.translatable("menu.custompaintings.search.hint"));
        search.setMaxLength(64);
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            scroll = 0;
            rebuildWidgets();
        });
        addRenderableWidget(search);

        int listTop = 70;
        int listBottom = Math.max(listTop + 30, this.height - 66);
        list = new PaintingList(this.minecraft, contentWidth, listBottom - listTop, listTop, 56, matches());
        list.updateSizeAndPosition(contentWidth, listBottom - listTop, left, listTop);
        list.children().stream().filter(row -> row.entry.id() == selectedId).findFirst()
                .ifPresentOrElse(list::setSelected, () -> selectedId = -1);
        list.setScrollAmount(scroll);
        addRenderableWidget(list);

        int gap = 6;
        int buttonWidth = Math.max(60, (contentWidth - gap * 2) / 3);
        int rowWidth = buttonWidth * 3 + gap * 2;
        int rowLeft = (this.width - rowWidth) / 2;
        int buttonsY = this.height - 52;
        takeButton = Button.builder(Component.translatable("menu.custompaintings.take"), button -> take(1))
                .bounds(rowLeft, buttonsY, buttonWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("menu.custompaintings.take.tooltip"))).build();
        addRenderableWidget(takeButton);
        takeStackButton = Button.builder(Component.translatable("menu.custompaintings.takeStack"), button -> take(16))
                .bounds(rowLeft + buttonWidth + gap, buttonsY, buttonWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("menu.custompaintings.takeStack.tooltip"))).build();
        addRenderableWidget(takeStackButton);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
                .bounds(rowLeft + (buttonWidth + gap) * 2, buttonsY, buttonWidth, 20).build());
        updateButtons();
    }

    private List<PaintingEntry> matches() {
        String needle = query.strip().toLowerCase(Locale.ROOT);
        return library.library().entries().stream()
                .filter(entry -> needle.isEmpty()
                        || entry.name().toLowerCase(Locale.ROOT).contains(needle)
                        || entry.sizeLabel().contains(needle))
                .toList();
    }

    private void take(int count) {
        PaintingEntry entry = selected();
        if (entry == null) {
            return;
        }
        if (CustomPaintingsClient.imports().givePainting(entry.id(), count)) {
            notice = Component.translatable("menu.custompaintings.requested", entry.name());
            noticeColor = SUCCESS;
        } else {
            notice = Component.translatable("menu.custompaintings.busy");
            noticeColor = WARNING;
        }
        updateButtons();
    }

    private PaintingEntry selected() {
        PaintingRow row = list == null ? null : list.getSelected();
        return row == null ? null : row.entry;
    }

    private void updateButtons() {
        if (takeButton == null) {
            return;
        }
        boolean ready = library.synced() && selected() != null && !CustomPaintingsClient.imports().state().busy();
        takeButton.active = ready;
        takeStackButton.active = ready;
    }

    @Override
    public void tick() {
        if (list != null) {
            PaintingEntry entry = selected();
            selectedId = entry == null ? -1 : entry.id();
            scroll = list.scrollAmount();
        }
        if (CustomPaintingsClient.paintings().generation() != displayedGeneration) {
            // Previews finished uploading; redraw rows without losing scroll or selection.
            displayedGeneration = CustomPaintingsClient.paintings().generation();
        }
        PaintingImportService.State state = CustomPaintingsClient.imports().state();
        if (state.phase() == PaintingImportService.Phase.FAILED && state.error() != null) {
            notice = Component.literal(state.error());
            noticeColor = ERROR;
        }
        updateButtons();
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        // Enter takes the highlighted painting, matching inventory muscle memory.
        if (event.isConfirmation()) {
            if (selected() != null) {
                take(1);
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 10, TEXT);
        graphics.centeredText(this.font, Component.translatable("menu.custompaintings.count",
                library.library().count()), this.width / 2, 26, SUCCESS);
        Component hint = !library.synced()
                ? Component.translatable("config.custompaintings.waitingSync")
                : Component.translatable("menu.custompaintings.hint");
        graphics.centeredText(this.font, this.font.plainSubstrByWidth(hint.getString(), this.width - 24),
                this.width / 2, this.height - 26, MUTED);
        if (notice != null) {
            graphics.centeredText(this.font, this.font.plainSubstrByWidth(notice.getString(), this.width - 24),
                    this.width / 2, this.height - 62, noticeColor);
        }
        if (library.synced() && list.children().isEmpty() && list.getHeight() >= 40) {
            int middle = list.getY() + list.getHeight() / 2 - 10;
            graphics.centeredText(this.font, Component.translatable(query.isEmpty()
                            ? "menu.custompaintings.empty" : "menu.custompaintings.noMatches"),
                    this.width / 2, middle, TEXT);
            if (query.isEmpty()) {
                graphics.centeredText(this.font, Component.translatable("menu.custompaintings.emptyHint"),
                        this.width / 2, middle + 14, MUTED);
            }
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    private static final class PaintingList extends ObjectSelectionList<PaintingRow> {
        private PaintingList(Minecraft minecraft, int width, int height, int y, int itemHeight,
                             List<PaintingEntry> entries) {
            super(minecraft, width, height, y, itemHeight);
            for (PaintingEntry entry : entries) {
                addEntry(new PaintingRow(entry));
            }
        }

        @Override
        public int getRowWidth() {
            return Math.max(80, this.getWidth() - 24);
        }
    }

    private static final class PaintingRow extends ObjectSelectionList.Entry<PaintingRow> {
        private final PaintingEntry entry;

        private PaintingRow(PaintingEntry entry) {
            this.entry = entry;
        }

        @Override
        public Component getNarration() {
            return Component.literal(entry.name() + ", " + entry.sizeLabel());
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            if (doubleClick) {
                CustomPaintingsClient.imports().givePainting(entry.id(), 1);
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getContentX();
            int y = getContentY();
            int width = getContentWidth();
            Minecraft minecraft = Minecraft.getInstance();
            graphics.fill(x, y, x + 48, y + 48, 0xFF202020);
            PaintingTextureManager.Texture texture = CustomPaintingsClient.paintings().preview(entry.id());
            if (texture != null) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, texture.id(), x + 2, y + 2,
                        0.0F, 0.0F, 44, 44, texture.width(), texture.height(),
                        texture.width(), texture.height());
            } else {
                graphics.centeredText(minecraft.font, "…", x + 24, y + 19, MUTED);
            }
            int textX = x + 56;
            int textWidth = Math.max(10, width - 60);
            graphics.text(minecraft.font, minecraft.font.plainSubstrByWidth(entry.name(), textWidth), textX, y + 5, TEXT);
            graphics.text(minecraft.font, entry.sizeLabel() + "  ·  "
                            + Component.translatable(entry.fit().translationKey()).getString(),
                    textX, y + 20, MUTED);
            Component source = entry.hasRecipe()
                    ? Component.translatable("menu.custompaintings.craftable", RecipeCatalog.get(entry.recipeId()).label())
                    : Component.translatable("menu.custompaintings.menuOnly");
            graphics.text(minecraft.font, minecraft.font.plainSubstrByWidth(source.getString(), textWidth),
                    textX, y + 35, entry.hasRecipe() ? SUCCESS : WARNING);
        }
    }
}
