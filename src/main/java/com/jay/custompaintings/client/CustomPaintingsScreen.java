package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.image.ImageImporter;
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

/** Mod Menu configuration with in-game importing, drag-and-drop, previews and import reports. */
public final class CustomPaintingsScreen extends Screen {
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFAAB2BD;
    private static final int SUCCESS = 0xFF9CCC65;
    private static final int WARNING = 0xFFFFD166;
    private static final int ERROR = 0xFFFF8080;

    private final Screen parent;
    private final PaintingTextureManager manager;
    private final PaintingImportService imports;
    private long displayedGeneration;
    private String selectedFile;
    private double scroll;
    private ImageList list;
    private Button uploadButton;
    private Button reloadButton;
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
    }

    @Override
    protected void init() {
        PaintingTextureManager.Snapshot snapshot = manager.snapshot();
        displayedGeneration = snapshot.generation();
        int margin = this.width < 360 ? 10 : 24;
        int contentWidth = Math.max(100, this.width - margin * 2);
        int listTop = 91;
        int listBottom = Math.max(listTop + 24, this.height - 79);
        list = new ImageList(this.minecraft, contentWidth, listBottom - listTop, listTop, 58);
        list.updateSizeAndPosition(contentWidth, listBottom - listTop, margin, listTop);
        if (selectedFile != null) {
            list.children().stream().filter(entry -> entry.image.fileName().equals(selectedFile))
                    .findFirst().ifPresent(list::setSelected);
        }
        list.setScrollAmount(scroll);
        addRenderableWidget(list);

        int gap = 6;
        int buttonWidth = Math.max(50, (contentWidth - gap * 3) / 4);
        int controlsWidth = buttonWidth * 4 + gap * 3;
        int left = (this.width - controlsWidth) / 2;
        int uploadY = 62;
        uploadButton = Button.builder(Component.translatable("config.custompaintings.upload"), button -> chooseFiles())
                .bounds(left, uploadY, buttonWidth * 2 + gap, 20)
                .tooltip(Tooltip.create(Component.translatable("config.custompaintings.upload.tooltip")))
                .build();
        addRenderableWidget(uploadButton);
        reloadButton = Button.builder(Component.translatable("config.custompaintings.reload"), button -> {
            clearNotice();
            imports.reloadPaintings();
        }).bounds(left + (buttonWidth + gap) * 2, uploadY, buttonWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("config.custompaintings.reload.tooltip"))).build();
        addRenderableWidget(reloadButton);
        detailsButton = Button.builder(Component.translatable("config.custompaintings.details"), button -> showDetails())
                .bounds(left + (buttonWidth + gap) * 3, uploadY, buttonWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("config.custompaintings.details.tooltip"))).build();
        addRenderableWidget(detailsButton);

        int bottom = this.height - 32;
        int bottomWidth = Math.max(50, (contentWidth - gap * 2) / 3);
        int bottomLeft = (this.width - bottomWidth * 3 - gap * 2) / 2;
        addRenderableWidget(Button.builder(Component.translatable("config.custompaintings.openFolder"), button -> openFolder())
                .bounds(bottomLeft, bottom, bottomWidth, 20).build());
        removeButton = Button.builder(Component.translatable("config.custompaintings.remove"), button -> confirmRemove())
                .bounds(bottomLeft + bottomWidth + gap, bottom, bottomWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("config.custompaintings.remove.tooltip"))).build();
        addRenderableWidget(removeButton);
        addRenderableWidget(Button.builder(Component.translatable("config.custompaintings.done"), button -> onClose())
                .bounds(bottomLeft + (bottomWidth + gap) * 2, bottom, bottomWidth, 20).build());
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
        if (chooser != null) {
            return;
        }
        startImport(files);
    }

    private void startImport(List<Path> files) {
        clearNotice();
        if (files.isEmpty()) {
            return;
        }
        if (files.size() > ImageImporter.MAX_BATCH_FILES) {
            setNotice(Component.translatable("config.custompaintings.import.tooMany", ImageImporter.MAX_BATCH_FILES), ERROR);
        } else if (!imports.importImages(files)) {
            setNotice(Component.translatable("config.custompaintings.import.busy"), WARNING);
        }
        updateControls();
    }

    private void openFolder() {
        try {
            Files.createDirectories(manager.directory());
            // 26.3 uses the SDL-backed Blaze3D API rather than the removed Util.OS helper.
            Blaze3D.openPath(manager.directory());
        } catch (IOException | RuntimeException exception) {
            CustomPaintingsMod.LOGGER.warn("Could not open the image folder", exception);
            setNotice(Component.translatable("config.custompaintings.folder.unavailable"), ERROR);
        }
    }

    private void confirmRemove() {
        ImageEntry entry = list == null ? null : list.getSelected();
        if (entry == null || imports.state().busy()) {
            return;
        }
        String fileName = entry.image.fileName();
        minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(confirmed -> {
            minecraft.gui.setScreen(this);
            if (confirmed) {
                removeFile(fileName);
            }
        }, Component.translatable("config.custompaintings.remove.title"),
                Component.translatable("config.custompaintings.remove.message", fileName),
                Component.translatable("config.custompaintings.remove.confirm"),
                Component.translatable("config.custompaintings.cancel")));
    }

    private void removeFile(String fileName) {
        clearNotice();
        if (imports.removeImage(fileName)) {
            selectedFile = null;
        } else {
            setNotice(Component.translatable("config.custompaintings.remove.failed", fileName), ERROR);
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
            ImageEntry selected = list.getSelected();
            selectedFile = selected == null ? null : selected.image.fileName();
            scroll = list.scrollAmount();
        }
        if (manager.snapshot().generation() != displayedGeneration) {
            rebuildWidgets();
        }
        updateControls();
    }

    private void updateControls() {
        if (uploadButton == null) {
            return;
        }
        boolean busy = imports.state().busy() || chooser != null;
        uploadButton.active = !busy;
        reloadButton.active = !busy;
        removeButton.active = !busy && list != null && list.getSelected() != null;
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
                snapshot.loadedCount(), snapshot.assignedCount()), this.width / 2, 25, SUCCESS);
        String directory = this.font.plainSubstrByWidth(manager.directory().toString(), this.width - 24);
        graphics.centeredText(this.font, directory, this.width / 2, 40, MUTED);

        Component status = notice != null ? notice : statusMessage(snapshot);
        int color = notice != null ? noticeColor : statusColor(snapshot);
        String fitted = this.font.plainSubstrByWidth(status.getString(), this.width - 24);
        graphics.centeredText(this.font, fitted, this.width / 2, this.height - 65, color);
        graphics.centeredText(this.font, Component.translatable("config.custompaintings.dropHint"),
                this.width / 2, this.height - 50, MUTED);

        if (snapshot.error() != null) {
            graphics.centeredText(this.font, this.font.plainSubstrByWidth(
                    Component.translatable("config.custompaintings.folderError", snapshot.error()).getString(), this.width - 30),
                    this.width / 2, list.getY() + 8, ERROR);
        } else if (snapshot.images().isEmpty() && list.getHeight() >= 42) {
            int middle = list.getY() + list.getHeight() / 2 - 12;
            graphics.centeredText(this.font, Component.translatable("config.custompaintings.empty"),
                    this.width / 2, middle, TEXT);
            graphics.centeredText(this.font, Component.translatable("config.custompaintings.emptyHint"),
                    this.width / 2, middle + 16, MUTED);
        }
    }

    private Component statusMessage(PaintingTextureManager.Snapshot snapshot) {
        PaintingImportService.State state = imports.state();
        return switch (state.phase()) {
            case IDLE -> Component.translatable("config.custompaintings.ready");
            case IMPORTING -> Component.translatable("config.custompaintings.import.progress",
                    state.completed(), state.total(), state.currentFile());
            case REMOVING -> Component.translatable("config.custompaintings.remove.progress", state.currentFile());
            case RELOADING -> Component.translatable("config.custompaintings.import.applying");
            case FINISHED -> state.report() == null
                    ? Component.translatable("config.custompaintings.reload.success")
                    : Component.translatable("config.custompaintings.import.result", state.report().importedCount(),
                    state.report().duplicateCount(), state.report().failedCount());
            case FAILED -> Component.translatable("config.custompaintings.import.failed", state.error());
        };
    }

    private int statusColor(PaintingTextureManager.Snapshot snapshot) {
        PaintingImportService.State state = imports.state();
        return switch (state.phase()) {
            case IDLE -> MUTED;
            case IMPORTING, REMOVING, RELOADING -> WARNING;
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
        this.minecraft.gui.setScreen(parent);
    }

    private static final class ImageList extends ObjectSelectionList<ImageEntry> {
        private ImageList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
            for (PaintingTextureManager.LoadedImage image : CustomPaintingsClient.paintings().snapshot().images()) {
                addEntry(new ImageEntry(image));
            }
        }

        @Override
        public int getRowWidth() {
            return Math.max(80, this.getWidth() - 24);
        }
    }

    private static final class ImageEntry extends ObjectSelectionList.Entry<ImageEntry> {
        private final PaintingTextureManager.LoadedImage image;

        private ImageEntry(PaintingTextureManager.LoadedImage image) {
            this.image = image;
        }

        @Override
        public Component getNarration() {
            return Component.literal(image.fileName() + ": ").append(status());
        }

        private Component status() {
            if (!image.successful()) {
                return Component.translatable("config.custompaintings.error", image.error());
            }
            if (image.replacements().isEmpty()) {
                return Component.translatable("config.custompaintings.unassigned");
            }
            var pixels = image.preview();
            String dimensions = " (" + pixels.width() + " × " + pixels.height() + ")";
            if (image.replacements().size() == 1) {
                return Component.translatable("config.custompaintings.replaces", image.replacements().getFirst().targetId() + dimensions);
            }
            return Component.translatable("config.custompaintings.replacesMany", image.replacements().size())
                    .append(Component.literal(dimensions));
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getContentX();
            int y = getContentY();
            int width = getContentWidth();
            Minecraft minecraft = Minecraft.getInstance();
            graphics.fill(x, y, x + 44, y + 44, 0xFF202020);
            Identifier texture = CustomPaintingsClient.paintings().previewTexture(image.fileName());
            if (image.successful() && texture != null) {
                var pixels = image.preview();
                float scale = Math.min(40.0F / pixels.width(), 40.0F / pixels.height());
                int drawnWidth = Math.max(1, Math.round(pixels.width() * scale));
                int drawnHeight = Math.max(1, Math.round(pixels.height() * scale));
                graphics.blit(RenderPipelines.GUI_TEXTURED, texture,
                        x + 2 + (40 - drawnWidth) / 2, y + 2 + (40 - drawnHeight) / 2,
                        0.0F, 0.0F, drawnWidth, drawnHeight, pixels.width(), pixels.height(), TEXT);
            } else {
                graphics.centeredText(minecraft.font, "!", x + 22, y + 17, ERROR);
            }
            int textX = x + 52;
            int textWidth = Math.max(10, width - 56);
            graphics.text(minecraft.font, minecraft.font.plainSubstrByWidth(image.fileName(), textWidth), textX, y + 3, TEXT);
            String statusText = minecraft.font.plainSubstrByWidth(status().getString(), textWidth);
            graphics.text(minecraft.font, statusText, textX, y + 20,
                    image.successful() ? (image.replacements().isEmpty() ? MUTED : SUCCESS) : ERROR);
            if (!image.successful()) {
                graphics.text(minecraft.font, "PNG / JPG / JPEG", textX, y + 35, MUTED);
            }
        }
    }
}
