package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.image.ImageImporter;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.RecipeCatalog;
import com.mojang.blaze3d.Blaze3D;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Mod Menu library: up to 10 named, previewed, craftable custom paintings. */
public final class CustomPaintingsScreen extends Screen {
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFAAB2BD;
    private static final int SUCCESS = 0xFF9CCC65;
    private static final int WARNING = 0xFFFFD166;
    private static final int ERROR = 0xFFFF8080;

    private final Screen parent;
    private final PaintingTextureManager manager;
    private final PaintingImportService imports;
    private final ClientPaintingLibrary library;
    private long displayedGeneration;
    private int selectedSlot = -1;
    private double scroll;
    private PaintingList list;
    private Button uploadButton;
    private Button reloadButton;
    private Button editButton;
    private Button applyButton;
    private Button discardButton;
    private Button removeButton;
    private Button detailsButton;
    private ImageFileChooser chooser;
    private Component notice;
    private int noticeColor;

    public CustomPaintingsScreen(Screen parent) {
        super(Component.translatable("config.custompaintings.title"));
        this.parent = parent;
        this.manager = CustomPaintingsClient.paintings();
        this.imports = CustomPaintingsClient.imports();
        this.library = CustomPaintingsClient.library();
    }

    @Override
    protected void init() {
        displayedGeneration = manager.snapshot().generation();
        int margin = this.width < 420 ? 10 : 24;
        int contentWidth = Math.max(120, this.width - margin * 2);
        int listTop = 92;
        int listBottom = Math.max(listTop + 24, this.height - 92);
        list = new PaintingList(this.minecraft, contentWidth, listBottom - listTop, listTop, 62);
        list.updateSizeAndPosition(contentWidth, listBottom - listTop, margin, listTop);
        list.children().stream().filter(entry -> entry.painting.entry().slot() == selectedSlot)
                .findFirst().ifPresent(list::setSelected);
        list.setScrollAmount(scroll);
        addRenderableWidget(list);

        int gap = 6;
        int buttonWidth = Math.max(50, (contentWidth - gap * 3) / 4);
        int controlsWidth = buttonWidth * 4 + gap * 3;
        int left = (this.width - controlsWidth) / 2;
        int uploadY = 62;
        uploadButton = Button.builder(Component.translatable("config.custompaintings.upload"), button -> chooseFiles())
                .bounds(left, uploadY, buttonWidth * 2 + gap, 20)
                .tooltip(Tooltip.create(Component.translatable("config.custompaintings.upload.tooltip"))).build();
        addRenderableWidget(uploadButton);
        reloadButton = Button.builder(Component.translatable("config.custompaintings.reload"), button -> {
            clearNotice();
            imports.reloadPaintings();
        }).bounds(left + (buttonWidth + gap) * 2, uploadY, buttonWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("config.custompaintings.reload.tooltip"))).build();
        addRenderableWidget(reloadButton);
        detailsButton = Button.builder(Component.translatable("config.custompaintings.details"), button -> showDetails())
                .bounds(left + (buttonWidth + gap) * 3, uploadY, buttonWidth, 20).build();
        addRenderableWidget(detailsButton);

        int bottom = this.height - 32;
        int editY = this.height - 58;
        int smallWidth = Math.max(48, (contentWidth - gap * 4) / 5);
        int smallLeft = (this.width - smallWidth * 5 - gap * 4) / 2;
        editButton = Button.builder(Component.translatable("config.custompaintings.rename"), button -> editSelected())
                .bounds(smallLeft, editY, smallWidth, 20).build();
        addRenderableWidget(editButton);
        removeButton = Button.builder(Component.translatable("config.custompaintings.remove"), button -> confirmRemove())
                .bounds(smallLeft + smallWidth + gap, editY, smallWidth, 20).build();
        addRenderableWidget(removeButton);
        applyButton = Button.builder(Component.translatable("config.custompaintings.apply"), button -> applyDrafts())
                .bounds(smallLeft + (smallWidth + gap) * 2, editY, smallWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("config.custompaintings.apply.tooltip"))).build();
        addRenderableWidget(applyButton);
        discardButton = Button.builder(Component.translatable("config.custompaintings.discard"), button -> discardDrafts())
                .bounds(smallLeft + (smallWidth + gap) * 3, editY, smallWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("config.custompaintings.discard.tooltip"))).build();
        addRenderableWidget(discardButton);
        addRenderableWidget(Button.builder(Component.translatable("config.custompaintings.done"), button -> onClose())
                .bounds(smallLeft + (smallWidth + gap) * 4, editY, smallWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("config.custompaintings.openFolder"), button -> openFolder())
                .bounds((this.width - 160) / 2, bottom, 160, 20)
                .tooltip(Tooltip.create(Component.translatable("config.custompaintings.openFolder.tooltip"))).build());
        updateControls();
    }

    private void chooseFiles() {
        if (imports.state().busy() || chooser != null) {
            return;
        }
        clearNotice();
        chooser = new ImageFileChooser(result -> {
            chooser = null;
            if (result.error() != null) {
                setNotice(Component.translatable("config.custompaintings.picker.error", result.error()), ERROR);
            } else if (result.cancelled()) {
                setNotice(Component.translatable("config.custompaintings.picker.cancelled"), MUTED);
            } else {
                startImport(result.files());
            }
            updateControls();
        });
        try {
            chooser.open();
        } catch (RuntimeException | LinkageError exception) {
            CustomPaintingsMod.LOGGER.warn("Native image picker unavailable; drag-and-drop remains available", exception);
            chooser.close();
            chooser = null;
            setNotice(Component.translatable("config.custompaintings.picker.unavailable"), WARNING);
        }
        updateControls();
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        if (chooser == null) {
            startImport(files);
        }
    }

    private void startImport(List<Path> files) {
        clearNotice();
        if (files.isEmpty()) {
            return;
        }
        if (!library.canEdit()) {
            setNotice(Component.translatable("config.custompaintings.noPermission"), ERROR);
        } else if (files.size() > ImageImporter.MAX_BATCH_FILES) {
            setNotice(Component.translatable("config.custompaintings.import.tooMany", ImageImporter.MAX_BATCH_FILES), ERROR);
        } else if (files.size() > library.library().freeSlots()) {
            setNotice(Component.translatable("config.custompaintings.libraryFull", library.library().freeSlots()), ERROR);
        } else if (!imports.importImages(files)) {
            setNotice(Component.translatable("config.custompaintings.import.busy"), WARNING);
        }
        updateControls();
    }

    private void editSelected() {
        PaintingEntry entry = selected();
        if (entry != null && !imports.state().busy()) {
            PaintingEntry draft = CustomPaintingsClient.drafts().displayEntry(entry.slot(), library.library());
            minecraft.gui.setScreen(new EditPaintingScreen(this, draft != null ? draft : entry));
        }
    }

    private void confirmRemove() {
        PaintingEntry entry = selected();
        if (entry == null || imports.state().busy()) {
            return;
        }
        // Removals are staged too: nothing leaves the server until Apply is pressed.
        // Pressing the same button again undoes just this slot's pending removal.
        if (CustomPaintingsClient.drafts().isRemoved(entry.slot())) {
            CustomPaintingsClient.drafts().discardSlot(entry.slot());
            setNotice(Component.translatable("config.custompaintings.remove.undone", entry.name()), MUTED);
        } else {
            CustomPaintingsClient.drafts().markRemoved(entry.slot(), library.library());
            setNotice(Component.translatable("config.custompaintings.remove.staged", entry.name()), WARNING);
        }
        updateControls();
    }

    private void applyDrafts() {
        clearNotice();
        if (!imports.applyDrafts()) {
            setNotice(Component.translatable("config.custompaintings.apply.empty"), MUTED);
        }
        updateControls();
    }

    private void discardDrafts() {
        CustomPaintingsClient.drafts().discard();
        setNotice(Component.translatable("config.custompaintings.discard.done"), MUTED);
        updateControls();
    }

    private PaintingEntry selected() {
        PaintingRow row = list == null ? null : list.getSelected();
        return row == null ? null : row.painting.entry();
    }

    private void openFolder() {
        try {
            Files.createDirectories(manager.directory());
            Blaze3D.openPath(manager.directory());
        } catch (IOException | RuntimeException exception) {
            CustomPaintingsMod.LOGGER.warn("Could not open the local image folder", exception);
            setNotice(Component.translatable("config.custompaintings.folder.unavailable"), ERROR);
        }
    }

    private void showDetails() {
        PaintingImportService.State state = imports.state();
        if (state.report() != null || state.error() != null) {
            minecraft.gui.setScreen(new ImportDetailsScreen(this, state));
        }
    }

    @Override
    public void tick() {
        if (list != null) {
            PaintingEntry entry = selected();
            selectedSlot = entry == null ? -1 : entry.slot();
            scroll = list.scrollAmount();
        }
        if (manager.snapshot().generation() != displayedGeneration) {
            rebuildWidgets();
        }
        int conflicts = CustomPaintingsClient.drafts().conflictCount();
        if (conflicts > 0) {
            CustomPaintingsClient.drafts().clearConflicts();
            setNotice(Component.translatable("config.custompaintings.draft.conflict", conflicts), WARNING);
        }
        updateControls();
    }

    private void updateControls() {
        if (uploadButton == null) {
            return;
        }
        boolean busy = imports.state().busy() || chooser != null;
        boolean editable = library.synced() && library.canEdit() && !busy;
        uploadButton.active = editable && library.library().freeSlots() > 0;
        reloadButton.active = !busy;
        PaintingEntry entry = selected();
        editButton.active = editable && entry != null;
        boolean pendingRemoval = entry != null && CustomPaintingsClient.drafts().isRemoved(entry.slot());
        removeButton.active = editable && entry != null;
        removeButton.setMessage(Component.translatable(pendingRemoval
                ? "config.custompaintings.remove.undo" : "config.custompaintings.remove"));
        removeButton.setTooltip(Tooltip.create(Component.translatable(pendingRemoval
                ? "config.custompaintings.remove.undo.tooltip" : "config.custompaintings.remove.staged.tooltip")));
        boolean hasDrafts = CustomPaintingsClient.drafts().hasChanges(library.library());
        applyButton.active = editable && hasDrafts;
        discardButton.active = editable && hasDrafts;
        int pending = CustomPaintingsClient.drafts().changeCount(library.library());
        applyButton.setMessage(pending > 0
                ? Component.translatable("config.custompaintings.apply.count", pending)
                : Component.translatable("config.custompaintings.apply"));
        PaintingImportService.State state = imports.state();
        detailsButton.active = !busy && (state.report() != null || state.error() != null);
        uploadButton.setMessage(Component.translatable(state.phase() == PaintingImportService.Phase.IMPORTING
                ? "config.custompaintings.importing" : "config.custompaintings.upload"));
    }

    private void setNotice(Component message, int color) {
        notice = message;
        noticeColor = color;
    }

    private void clearNotice() {
        notice = null;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        PaintingTextureManager.Snapshot snapshot = manager.snapshot();
        graphics.centeredText(this.font, this.title, this.width / 2, 9, TEXT);
        graphics.centeredText(this.font, Component.translatable("config.custompaintings.summary",
                library.library().count(), PaintingEntry.MAX_PAINTINGS), this.width / 2, 25, SUCCESS);
        int pending = CustomPaintingsClient.drafts().changeCount(library.library());
        String hint = !library.synced()
                ? Component.translatable("config.custompaintings.waitingSync").getString()
                : pending > 0
                ? Component.translatable("config.custompaintings.draft.pending", pending).getString()
                : (library.canEdit() ? Component.translatable("config.custompaintings.editable").getString()
                : Component.translatable("config.custompaintings.readOnly").getString());
        graphics.centeredText(this.font, this.font.plainSubstrByWidth(hint, this.width - 24), this.width / 2, 40, MUTED);

        Component status = notice != null ? notice : statusMessage();
        int color = notice != null ? noticeColor : statusColor();
        graphics.centeredText(this.font, this.font.plainSubstrByWidth(status.getString(), this.width - 24),
                this.width / 2, this.height - 78, color);
        if (snapshot.error() != null) {
            graphics.centeredText(this.font, this.font.plainSubstrByWidth(
                    Component.translatable("config.custompaintings.folderError", snapshot.error()).getString(), this.width - 30),
                    this.width / 2, list.getY() + 8, ERROR);
        } else if (library.library().entries().isEmpty() && list.getHeight() >= 42) {
            int middle = list.getY() + list.getHeight() / 2 - 12;
            graphics.centeredText(this.font, Component.translatable("config.custompaintings.empty"),
                    this.width / 2, middle, TEXT);
            graphics.centeredText(this.font, Component.translatable("config.custompaintings.emptyHint"),
                    this.width / 2, middle + 16, MUTED);
        }
    }

    private Component statusMessage() {
        PaintingImportService.State state = imports.state();
        return switch (state.phase()) {
            case IDLE -> Component.translatable("config.custompaintings.ready");
            case IMPORTING -> Component.translatable("config.custompaintings.import.progress",
                    state.completed(), state.total(), state.currentFile());
            case APPLYING -> Component.translatable("config.custompaintings.apply.progress");
            case REMOVING -> Component.translatable("config.custompaintings.remove.progress", state.currentFile());
            case RELOADING -> Component.translatable("config.custompaintings.import.applying");
            case FINISHED -> state.report() == null
                    ? Component.translatable("config.custompaintings.operation.success")
                    : Component.translatable("config.custompaintings.import.result", state.report().importedCount(),
                    state.report().duplicateCount(), state.report().failedCount());
            case FAILED -> Component.translatable("config.custompaintings.import.failed", state.error());
        };
    }

    private int statusColor() {
        PaintingImportService.State state = imports.state();
        return switch (state.phase()) {
            case IDLE -> MUTED;
            case IMPORTING, APPLYING, REMOVING, RELOADING -> WARNING;
            case FINISHED -> state.report() != null && state.report().failedCount() > 0 ? WARNING : SUCCESS;
            case FAILED -> ERROR;
        };
    }

    @Override
    public void removed() {
        if (chooser != null) {
            chooser.close();
            chooser = null;
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private static final class PaintingList extends ObjectSelectionList<PaintingRow> {
        private PaintingList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
            for (PaintingTextureManager.LoadedImage image : CustomPaintingsClient.paintings().snapshot().images()) {
                addEntry(new PaintingRow(image));
            }
        }

        @Override
        public int getRowWidth() {
            return Math.max(80, this.getWidth() - 24);
        }
    }

    private static final class PaintingRow extends ObjectSelectionList.Entry<PaintingRow> {
        private final PaintingTextureManager.LoadedImage painting;

        private PaintingRow(PaintingTextureManager.LoadedImage painting) {
            this.painting = painting;
        }

        @Override
        public Component getNarration() {
            return Component.literal(displayEntry().name() + ": ").append(status());
        }

        private PaintingEntry displayEntry() {
            int slot = painting.entry().slot();
            PaintingEntry draft = CustomPaintingsClient.drafts().displayEntry(slot, CustomPaintingsClient.library().library());
            return draft != null ? draft : painting.entry();
        }

        private Component status() {
            if (!painting.successful()) {
                return Component.translatable("config.custompaintings.error", painting.error());
            }
            int slot = painting.entry().slot();
            if (CustomPaintingsClient.drafts().isRemoved(slot)) {
                return Component.translatable("config.custompaintings.draft.pendingRemoval");
            }
            PaintingEntry entry = displayEntry();
            Component base = Component.translatable("config.custompaintings.entryStatus", entry.sizeLabel(),
                    Component.translatable(entry.fit().translationKey()).getString(),
                    RecipeCatalog.get(entry.recipeId()).label());
            PaintingEntry applied = CustomPaintingsClient.library().library().get(slot).orElse(null);
            if (applied != null && !applied.equals(entry)) {
                return Component.literal(base.getString()).append(Component.translatable("config.custompaintings.draft.pendingSuffix"));
            }
            return base;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getContentX();
            int y = getContentY();
            int width = getContentWidth();
            Minecraft minecraft = Minecraft.getInstance();
            graphics.fill(x, y, x + 48, y + 48, 0xFF202020);
            Identifier texture = CustomPaintingsClient.paintings().previewTexture(painting.entry().slot());
            if (painting.successful() && texture != null) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x + 2, y + 2,
                        0.0F, 0.0F, 44, 44, 96, 96, TEXT);
            } else {
                graphics.centeredText(minecraft.font, "!", x + 24, y + 19, ERROR);
            }
            int textX = x + 56;
            int textWidth = Math.max(10, width - 60);
            graphics.text(minecraft.font, minecraft.font.plainSubstrByWidth(displayEntry().name(), textWidth), textX, y + 4, TEXT);
            String statusText = minecraft.font.plainSubstrByWidth(status().getString(), textWidth);
            graphics.text(minecraft.font, statusText, textX, y + 21, painting.successful() ? SUCCESS : ERROR);
            graphics.text(minecraft.font, Component.translatable("config.custompaintings.slot", painting.entry().slot() + 1),
                    textX, y + 36, MUTED);
        }
    }
}
