package com.jay.custompaintings.client;

import com.jay.custompaintings.image.ImageImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Scrollable per-file import results, including failures that a one-line toast would hide. */
public final class ImportDetailsScreen extends Screen {
    private final Screen parent;
    private final PaintingImportService.State state;

    public ImportDetailsScreen(Screen parent, PaintingImportService.State state) {
        super(Component.translatable("config.custompaintings.details.title"));
        this.parent = parent;
        this.state = state;
    }

    @Override
    protected void init() {
        int width = Math.max(120, this.width - 40);
        ResultList list = new ResultList(this.minecraft, width, Math.max(40, this.height - 100), 55, 52);
        list.updateSizeAndPosition(width, Math.max(40, this.height - 100), 20, 55);
        addRenderableWidget(list);
        addRenderableWidget(Button.builder(Component.translatable("config.custompaintings.back"), button -> onClose())
                .bounds((this.width - 150) / 2, this.height - 32, 150, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
        ImageImporter.Report report = state.report();
        Component summary = report == null
                ? Component.translatable("config.custompaintings.import.failed", state.error())
                : Component.translatable("config.custompaintings.import.result", report.importedCount(),
                report.duplicateCount(), report.failedCount());
        graphics.centeredText(this.font, this.font.plainSubstrByWidth(summary.getString(), this.width - 30),
                this.width / 2, 31, state.error() != null ? 0xFFFF8080 : 0xFF9CCC65);
        if (state.error() != null && report != null) {
            graphics.centeredText(this.font, this.font.plainSubstrByWidth(state.error(), this.width - 30),
                    this.width / 2, 44, 0xFFFF8080);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }

    private final class ResultList extends ObjectSelectionList<ResultEntry> {
        private ResultList(Minecraft minecraft, int width, int height, int y, int itemHeight) {
            super(minecraft, width, height, y, itemHeight);
            if (state.report() != null) {
                for (ImageImporter.Result result : state.report().results()) {
                    addEntry(new ResultEntry(result));
                }
            }
        }

        @Override
        public int getRowWidth() {
            return Math.max(100, getWidth() - 24);
        }
    }

    private final class ResultEntry extends ObjectSelectionList.Entry<ResultEntry> {
        private final ImageImporter.Result result;

        private ResultEntry(ImageImporter.Result result) {
            this.result = result;
        }

        private Component description() {
            return switch (result.status()) {
                case IMPORTED -> Component.translatable("config.custompaintings.import.savedAs", result.fileName());
                case DUPLICATE -> Component.translatable("config.custompaintings.import.duplicate",
                        result.fileName() == null ? result.sourceName() : result.fileName());
                case FAILED -> Component.translatable("config.custompaintings.import.itemFailed", result.detail());
            };
        }

        @Override
        public Component getNarration() {
            return Component.literal(result.sourceName() + ": ").append(description());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float delta) {
            int x = getContentX() + 4;
            int y = getContentY() + 3;
            int width = Math.max(20, getContentWidth() - 8);
            int color = switch (result.status()) {
                case IMPORTED -> 0xFF9CCC65;
                case DUPLICATE -> 0xFFFFD166;
                case FAILED -> 0xFFFF8080;
            };
            graphics.text(font, font.plainSubstrByWidth(result.sourceName(), width), x, y, 0xFFFFFFFF);
            List<FormattedCharSequence> lines = font.split(description(), width);
            for (int i = 0; i < Math.min(3, lines.size()); i++) {
                graphics.text(font, lines.get(i), x, y + 14 + i * 10, color);
            }
        }
    }
}
