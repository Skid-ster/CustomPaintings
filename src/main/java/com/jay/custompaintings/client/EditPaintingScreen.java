package com.jay.custompaintings.client;

import com.jay.custompaintings.image.ImageFit;
import com.jay.custompaintings.image.ImageProcessor;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingSizes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** Full editor: name, independent 1–8 block dimensions, fit mode, and auto-size from the master image. */
public final class EditPaintingScreen extends Screen {
    private final Screen parent;
    private final PaintingEntry entry;
    private EditBox nameBox;
    private BlockSlider widthSlider;
    private BlockSlider heightSlider;
    private CycleButton<ImageFit> fitButton;
    private Button saveButton;
    private String error;

    public EditPaintingScreen(Screen parent, PaintingEntry entry) {
        super(Component.translatable("config.custompaintings.edit.title"));
        this.parent = parent;
        this.entry = entry;
    }

    @Override
    protected void init() {
        int boxWidth = Math.min(340, this.width - 32);
        int left = (this.width - boxWidth) / 2;
        nameBox = new EditBox(this.font, left, 44, boxWidth, 20,
                Component.translatable("config.custompaintings.rename.field"));
        nameBox.setMaxLength(48);
        nameBox.setValue(entry.name());
        nameBox.setResponder(value -> {
            error = null;
            try {
                PaintingEntry.normalizeName(value);
            } catch (IllegalArgumentException exception) {
                error = exception.getMessage();
            }
            updateSaveActive();
        });
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        int controlX = left + 80;
        int controlWidth = Math.max(80, boxWidth - 80);
        widthSlider = new BlockSlider(controlX, 72, controlWidth, 20,
                Component.translatable("config.custompaintings.edit.width"), entry.width());
        heightSlider = new BlockSlider(controlX, 96, controlWidth, 20,
                Component.translatable("config.custompaintings.edit.height"), entry.height());
        addRenderableWidget(widthSlider);
        addRenderableWidget(heightSlider);

        fitButton = CycleButton.<ImageFit>builder(fit -> Component.translatable(fit.translationKey()), entry.fit())
                .withValues(ImageFit.values())
                .create(controlX, 120, controlWidth, 20, Component.translatable("config.custompaintings.fit"));
        addRenderableWidget(fitButton);
        addRenderableWidget(Button.builder(Component.translatable("config.custompaintings.edit.auto"), button -> autoSize())
                .bounds(controlX, 144, controlWidth, 20).build());

        saveButton = Button.builder(Component.translatable("config.custompaintings.edit.save"), button -> save())
                .bounds(left, this.height - 56, (boxWidth - 6) / 2, 20).build();
        addRenderableWidget(saveButton);
        addRenderableWidget(Button.builder(Component.translatable("config.custompaintings.cancel"), button -> onClose())
                .bounds(left + (boxWidth + 6) / 2, this.height - 56, (boxWidth - 6) / 2, 20).build());
        updateSaveActive();
    }

    private void updateSaveActive() {
        if (saveButton != null) {
            saveButton.active = error == null && !CustomPaintingsClient.imports().state().busy();
        }
    }

    private void autoSize() {
        byte[] png = CustomPaintingsClient.library().image(entry.slot()).orElse(null);
        if (png == null) {
            error = Component.translatable("config.custompaintings.waitingSync").getString();
            return;
        }
        try {
            BufferedImage image = ImageProcessor.readBytes(png, entry.imageFile());
            PaintingSizes.Size size = PaintingSizes.auto(image.getWidth(), image.getHeight());
            widthSlider.setBlocks(size.width());
            heightSlider.setBlocks(size.height());
            error = null;
        } catch (IOException | RuntimeException exception) {
            error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
        updateSaveActive();
    }

    private void save() {
        try {
            String name = PaintingEntry.normalizeName(nameBox.getValue());
            PaintingSizes.Size size = PaintingSizes.of(widthSlider.blocks(), heightSlider.blocks());
            if (CustomPaintingsClient.imports().adjust(entry.slot(), name, size, fitButton.getValue())) {
                onClose();
            } else {
                error = Component.translatable("config.custompaintings.import.busy").getString();
            }
        } catch (IllegalArgumentException exception) {
            error = exception.getMessage();
        }
        updateSaveActive();
    }

    @Override
    public void tick() {
        super.tick();
        updateSaveActive();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("config.custompaintings.edit.hint"),
                this.width / 2, 30, 0xFFAAB2BD);
        int boxWidth = Math.min(340, this.width - 32);
        int left = (this.width - boxWidth) / 2;
        graphics.fill(left, 72, left + 64, 136, 0xFF202020);
        var texture = CustomPaintingsClient.paintings().previewTexture(entry.slot());
        if (texture != null) {
            graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture,
                    left + 2, 74, 0.0F, 0.0F, 60, 60, 96, 96, 0xFFFFFFFF);
        }
        graphics.centeredText(this.font, Component.translatable("config.custompaintings.edit.size",
                widthSlider.blocks(), heightSlider.blocks()), this.width / 2, 172, 0xFFAAB2BD);
        if (error != null) {
            graphics.centeredText(this.font, this.font.plainSubstrByWidth(error, this.width - 30),
                    this.width / 2, this.height - 76, 0xFFFF8080);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    private static final class BlockSlider extends AbstractSliderButton {
        private final Component label;

        private BlockSlider(int x, int y, int width, int height, Component label, int initial) {
            super(x, y, width, height, label, (Mth.clamp(initial, 1, 8) - 1) / 7.0);
            this.label = label;
            updateMessage();
        }

        int blocks() {
            return Mth.clamp(Mth.floor(this.value * 7.0 + 0.5) + 1, 1, 8);
        }

        void setBlocks(int blocks) {
            this.value = (Mth.clamp(blocks, 1, 8) - 1) / 7.0;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(label.copy().append(": " + blocks()));
        }

        @Override
        protected void applyValue() {
            updateMessage();
        }
    }
}
