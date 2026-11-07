package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
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

/** Scrollable, keyboard-navigable Mod Menu screen with actual loaded-image previews. */
public final class CustomPaintingsScreen extends Screen {
    private final Screen parent;
    private final PaintingTextureManager manager;
    private long displayedGeneration;

    public CustomPaintingsScreen(Screen parent) {
        super(Component.translatable("config.custompaintings.title"));
        this.parent = parent;
        this.manager = CustomPaintingsClient.paintings();
    }

    @Override
    protected void init() {
        PaintingTextureManager.Snapshot snapshot = manager.snapshot();
        displayedGeneration = snapshot.generation();
        int listHeight = Math.max(40, this.height - 136);
        ImageList list = new ImageList(this.minecraft, Math.max(100, this.width - 48), listHeight, 76, 58);
        list.updateSizeAndPosition(Math.max(100, this.width - 48), listHeight, 24, 76);
        addRenderableWidget(list);

        int buttonWidth = Math.max(60, Math.min(150, (this.width - 48) / 3));
        int left = (this.width - buttonWidth * 3 - 16) / 2;
        int bottom = this.height - 42;
        addRenderableWidget(Button.builder(Component.translatable("config.custompaintings.openFolder"), button -> {
            try {
                Files.createDirectories(manager.directory());
                // 26.3 moved folder opening from Util.OS to the SDL-backed Blaze3D API.
                Blaze3D.openPath(manager.directory());
            } catch (IOException | RuntimeException exception) {
                CustomPaintingsMod.LOGGER.warn("Could not open the image folder", exception);
                button.setMessage(Component.literal("Folder unavailable"));
            }
        }).bounds(left, bottom, buttonWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("config.custompaintings.reload"), button -> {
            button.active = false;
            this.minecraft.reloadResourcePacks().whenComplete((ignored, error) -> this.minecraft.execute(() -> button.active = true));
        }).bounds(left + buttonWidth + 8, bottom, buttonWidth, 20)
                .tooltip(Tooltip.create(Component.literal("Reloads all resource packs. You can also press F3 + T.")))
                .build());
        addRenderableWidget(Button.builder(Component.translatable("config.custompaintings.done"), button -> onClose())
                .bounds(left + (buttonWidth + 8) * 2, bottom, buttonWidth, 20).build());
    }

    @Override
    public void tick() {
        if (manager.snapshot().generation() != displayedGeneration) {
            rebuildWidgets();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        PaintingTextureManager.Snapshot snapshot = manager.snapshot();
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
        String directory = this.font.plainSubstrByWidth(manager.directory().toString(), this.width - 40);
        graphics.centeredText(this.font, directory, this.width / 2, 32, 0xFFA0A0A0);
        graphics.centeredText(this.font, Component.translatable("config.custompaintings.summary",
                snapshot.loadedCount(), snapshot.assignedCount()), this.width / 2, 48, 0xFFC8E6C9);
        if (snapshot.error() != null) {
            graphics.centeredText(this.font, Component.translatable("config.custompaintings.folderError", snapshot.error()),
                    this.width / 2, 94, 0xFFFF8080);
        } else if (snapshot.images().isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("config.custompaintings.empty"),
                    this.width / 2, 94, 0xFFFFFFFF);
            graphics.centeredText(this.font, Component.translatable("config.custompaintings.emptyHint"),
                    this.width / 2, 110, 0xFFA0A0A0);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
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
                        0.0F, 0.0F, drawnWidth, drawnHeight, pixels.width(), pixels.height(), 0xFFFFFFFF);
            } else {
                graphics.centeredText(minecraft.font, "!", x + 22, y + 17, 0xFFFF8080);
            }
            int textX = x + 52;
            int textWidth = Math.max(10, width - 56);
            graphics.text(minecraft.font, minecraft.font.plainSubstrByWidth(image.fileName(), textWidth), textX, y + 3, 0xFFFFFFFF);
            String statusText = minecraft.font.plainSubstrByWidth(status().getString(), textWidth);
            graphics.text(minecraft.font, statusText, textX, y + 20,
                    image.successful() ? (image.replacements().isEmpty() ? 0xFFB0B0B0 : 0xFF9CCC65) : 0xFFFF8080);
            if (!image.successful()) {
                graphics.text(minecraft.font, "PNG / JPG / JPEG", textX, y + 35, 0xFF909090);
            }
        }
    }
}
