package com.jay.custompaintings.client;

import com.jay.custompaintings.image.ImageFit;
import com.jay.custompaintings.image.ImageProcessor;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingSizes;
import com.jay.custompaintings.library.RecipeCatalog;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.awt.image.BufferedImage;
import java.util.function.IntConsumer;

/**
 * Draft-only metadata editor: name, canvas size, and fit. Changes are staged locally and
 * committed to the server only when the player returns to the library and presses Apply.
 * The preview is composed from the unchanged master at the draft's real aspect ratio.
 */
public final class EditPaintingScreen extends Screen {
    private final Screen parent;
    private final PaintingEntry original;
    private EditBox nameBox;
    private Button saveButton;
    private Button revertButton;
    private PaintingDraftPreview preview;
    private String name;
    private int canvasWidth;
    private int canvasHeight;
    private ImageFit fit;
    private boolean leaving;
    private boolean previewDirty;
    private int previewDelay;
    private String error;

    public EditPaintingScreen(Screen parent, PaintingEntry entry) {
        super(Component.translatable("config.custompaintings.edit.title"));
        this.parent = parent;
        this.original = entry;
        this.name = entry.name();
        this.canvasWidth = entry.width();
        this.canvasHeight = entry.height();
        this.fit = entry.fit();
    }

    @Override
    protected void init() {
        int contentWidth = Math.min(420, Math.max(280, this.width - 32));
        int left = (this.width - contentWidth) / 2;
        int previewSize = Math.min(112, Math.max(72, contentWidth / 2 - 16));
        int controlsX = left + previewSize + 16;
        int controlsWidth = contentWidth - previewSize - 16;

        if (preview != null) {
            preview.close();
        }
        preview = new PaintingDraftPreview();
        requestPreviewNow();

        nameBox = new EditBox(this.font, controlsX, 58, controlsWidth, 20,
                Component.translatable("config.custompaintings.rename.field"));
        nameBox.setValue(name);
        nameBox.setMaxLength(48);
        nameBox.setResponder(value -> {
            name = value;
            error = null;
            updateSaveState();
        });
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        addRenderableWidget(new BlockSlider(controlsX, 104, controlsWidth,
                Component.translatable("config.custompaintings.edit.width"), canvasWidth, value -> {
            canvasWidth = value;
            markPreviewDirty();
        }));
        addRenderableWidget(new BlockSlider(controlsX, 130, controlsWidth,
                Component.translatable("config.custompaintings.edit.height"), canvasHeight, value -> {
            canvasHeight = value;
            markPreviewDirty();
        }));
        CycleButton<ImageFit> fitButton = CycleButton.<ImageFit>builder(value -> Component.translatable(value.translationKey()), fit)
                .withValues(ImageFit.values())
                .create(controlsX, 170, controlsWidth, 20, Component.translatable("config.custompaintings.fit"),
                        (button, value) -> {
                            fit = value;
                            markPreviewDirty();
                        });
        fitButton.setTooltip(Tooltip.create(Component.translatable("config.custompaintings.fit.tooltip")));
        addRenderableWidget(fitButton);

        Button autoButton = Button.builder(Component.translatable("config.custompaintings.edit.auto"), button -> autoSize())
                .bounds(controlsX, 196, controlsWidth, 20).build();
        autoButton.setTooltip(Tooltip.create(Component.translatable("config.custompaintings.autoSize.tooltip")));
        addRenderableWidget(autoButton);

        revertButton = Button.builder(Component.translatable("config.custompaintings.edit.revert"), button -> {
            CustomPaintingsClient.drafts().discardSlot(original.slot());
            returnToParent();
        }).bounds(left, 196, previewSize, 20).build();
        revertButton.setTooltip(Tooltip.create(Component.translatable("config.custompaintings.edit.revert.tooltip")));
        addRenderableWidget(revertButton);

        int buttonWidth = (contentWidth - 8) / 2;
        saveButton = Button.builder(Component.translatable("config.custompaintings.edit.save"), button -> saveDraft())
                .bounds(left, this.height - 32, buttonWidth, 20).build();
        saveButton.setTooltip(Tooltip.create(Component.translatable("config.custompaintings.edit.save.tooltip")));
        addRenderableWidget(saveButton);
        addRenderableWidget(Button.builder(Component.translatable("config.custompaintings.cancel"), button -> onClose())
                .bounds(left + buttonWidth + 8, this.height - 32, buttonWidth, 20).build());
        updateSaveState();
    }

    @Override
    public void tick() {
        super.tick();
        if (previewDirty && --previewDelay <= 0) {
            previewDirty = false;
            requestPreviewNow();
        }
        updateSaveState();
    }

    private void markPreviewDirty() {
        previewDirty = true;
        previewDelay = 3;
        updateSaveState();
    }

    private void requestPreviewNow() {
        if (preview != null) {
            CustomPaintingsClient.library().image(original.slot())
                    .ifPresent(png -> preview.request(currentDraft(), png));
        }
    }

    private void autoSize() {
        byte[] png = CustomPaintingsClient.library().image(original.slot()).orElse(null);
        if (png == null) {
            error = Component.translatable("config.custompaintings.waitingSync").getString();
            return;
        }
        try {
            BufferedImage image = ImageProcessor.readBytes(png, original.imageFile());
            PaintingSizes.Size size = PaintingSizes.auto(image.getWidth(), image.getHeight());
            canvasWidth = size.width();
            canvasHeight = size.height();
            error = null;
            // Rebuild controls from the field state; init() no longer resets to the original entry.
            rebuildWidgets();
        } catch (Exception exception) {
            error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
    }

    private PaintingEntry currentDraft() {
        return new PaintingEntry(original.slot(), name, canvasWidth, canvasHeight, fit,
                original.imageFile(), original.sha256(), original.recipeId());
    }

    private boolean hasUnsavedChanges() {
        try {
            return !currentDraft().equals(original) || !name.equals(original.name());
        } catch (RuntimeException invalid) {
            return true;
        }
    }

    private void updateSaveState() {
        if (saveButton == null || nameBox == null) {
            return;
        }
        try {
            currentDraft();
            boolean changed = hasUnsavedChanges();
            saveButton.active = !CustomPaintingsClient.imports().state().busy() && changed;
            saveButton.setTooltip(Tooltip.create(Component.translatable(changed
                    ? "config.custompaintings.edit.save.tooltip" : "config.custompaintings.edit.noChanges")));
            nameBox.setTextColor(0xFFE0E0E0);
        } catch (RuntimeException exception) {
            saveButton.active = false;
            nameBox.setTextColor(0xFFFF8080);
            saveButton.setTooltip(Tooltip.create(Component.literal(exception.getMessage())));
        }
        if (revertButton != null) {
            revertButton.active = CustomPaintingsClient.drafts().hasSlotChanges(original.slot(),
                    CustomPaintingsClient.library().library()) || hasUnsavedChanges();
        }
    }

    private void saveDraft() {
        try {
            PaintingEntry staged = currentDraft();
            if (!CustomPaintingsClient.imports().state().busy()) {
                CustomPaintingsClient.drafts().stage(staged, CustomPaintingsClient.library().library());
                returnToParent();
            }
        } catch (RuntimeException exception) {
            error = exception.getMessage();
            nameBox.setTextColor(0xFFFF8080);
        }
    }

    private void returnToParent() {
        leaving = true;
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        if (leaving || !hasUnsavedChanges()) {
            returnToParent();
            return;
        }
        minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
            if (confirmed) {
                returnToParent();
            } else {
                // Field state survives re-init, so Keep Editing restores the exact draft.
                minecraft.gui.setScreen(this);
            }
        }, Component.translatable("config.custompaintings.edit.unsaved.title"),
                Component.translatable("config.custompaintings.edit.unsaved.message"),
                Component.translatable("config.custompaintings.edit.unsaved.discard"),
                Component.translatable("config.custompaintings.edit.unsaved.keep")));
    }

    @Override
    public void removed() {
        super.removed();
        if (preview != null) {
            preview.close();
            preview = null;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int contentWidth = Math.min(420, Math.max(280, this.width - 32));
        int left = (this.width - contentWidth) / 2;
        int previewSize = Math.min(112, Math.max(72, contentWidth / 2 - 16));
        int controlsX = left + previewSize + 16;
        graphics.centeredText(this.font, this.title, this.width / 2, 14, 0xFFFFFFFF);
        graphics.text(this.font, Component.translatable("config.custompaintings.recipe",
                RecipeCatalog.get(original.recipeId()).label()), left, 36, 0xFFB8B8C8);
        if (preview != null) {
            preview.draw(graphics, left, 58, previewSize, previewSize);
        }
        graphics.centeredText(this.font, Component.translatable("config.custompaintings.edit.preview",
                canvasWidth, canvasHeight, Component.translatable(fit.translationKey()).getString()),
                left + previewSize / 2, 174, 0xFF9CCC65);
        graphics.text(this.font, Component.translatable("config.custompaintings.rename.field"), controlsX, 46, 0xFFA0A0B0);
        graphics.text(this.font, Component.translatable("config.custompaintings.fit"), controlsX, 158, 0xFFA0A0B0);
        Component hint = Component.translatable(error != null ? "config.custompaintings.edit.previewError"
                : preview != null && preview.loading() ? "config.custompaintings.edit.previewLoading"
                : "config.custompaintings.edit.hint");
        String hintText = error != null ? error : hint.getString();
        graphics.centeredText(this.font, this.font.plainSubstrByWidth(hintText, this.width - 30),
                this.width / 2, this.height - 58, error != null ? 0xFFFF8080 : 0xFF9E9EAE);
    }

    private static final class BlockSlider extends AbstractSliderButton {
        private final Component label;
        private final IntConsumer onChange;

        private BlockSlider(int x, int y, int width, Component label, int initial, IntConsumer onChange) {
            super(x, y, width, 20, label, (Mth.clamp(initial, 1, 8) - 1) / 7.0);
            this.label = label;
            this.onChange = onChange;
            updateMessage();
        }

        int blocks() {
            return Mth.clamp(Mth.floor(this.value * 7.0 + 0.5) + 1, 1, 8);
        }

        @Override
        protected void updateMessage() {
            setMessage(label.copy().append(": " + blocks()));
        }

        @Override
        protected void applyValue() {
            onChange.accept(blocks());
        }
    }
}
