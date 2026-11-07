package com.jay.custompaintings.client;

import com.jay.custompaintings.library.PaintingEntry;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Inline name editor with validation and a live preview. */
public final class EditPaintingScreen extends Screen {
    private final Screen parent;
    private final PaintingEntry entry;
    private EditBox nameBox;
    private Button saveButton;
    private String error;

    public EditPaintingScreen(Screen parent, PaintingEntry entry) {
        super(Component.translatable("config.custompaintings.rename.title"));
        this.parent = parent;
        this.entry = entry;
    }

    @Override
    protected void init() {
        int boxWidth = Math.min(320, this.width - 40);
        nameBox = new EditBox(this.font, (this.width - boxWidth) / 2, 70, boxWidth, 20,
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
            if (saveButton != null) {
                saveButton.active = error == null && !CustomPaintingsClient.imports().state().busy();
            }
        });
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);
        saveButton = Button.builder(Component.translatable("config.custompaintings.rename.save"), button -> save())
                .bounds((this.width - 150) / 2, this.height - 56, 150, 20).build();
        addRenderableWidget(saveButton);
        addRenderableWidget(Button.builder(Component.translatable("config.custompaintings.cancel"), button -> onClose())
                .bounds((this.width - 150) / 2, this.height - 32, 150, 20).build());
        saveButton.active = error == null;
    }

    private void save() {
        try {
            String name = PaintingEntry.normalizeName(nameBox.getValue());
            if (CustomPaintingsClient.imports().rename(entry.slot(), name)) {
                onClose();
            } else {
                error = Component.translatable("config.custompaintings.import.busy").getString();
            }
        } catch (IllegalArgumentException exception) {
            error = exception.getMessage();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
        graphics.centeredText(this.font, Component.translatable("config.custompaintings.rename.hint", entry.sizeLabel()),
                this.width / 2, 40, 0xFFAAB2BD);
        var texture = CustomPaintingsClient.paintings().previewTexture(entry.slot());
        if (texture != null) {
            graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, texture,
                    (this.width - 64) / 2, 104, 0.0F, 0.0F, 64, 64, 96, 96, 0xFFFFFFFF);
        }
        if (error != null) {
            graphics.centeredText(this.font, this.font.plainSubstrByWidth(error, this.width - 30),
                    this.width / 2, 180, 0xFFFF8080);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
