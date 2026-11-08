package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.image.ImageProcessor;
import com.jay.custompaintings.library.PaintingEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.awt.image.BufferedImage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aspect-correct preview of an unapplied draft. Composition happens off the client thread;
 * only the latest request uploads a GPU texture, and closing the editor releases it.
 * This never touches the painting atlas or triggers a resource reload.
 */
public final class PaintingDraftPreview implements AutoCloseable {
    private static final AtomicLong IDS = new AtomicLong();
    private static final int MAX_PREVIEW_SIDE = 256;
    private final Minecraft minecraft = Minecraft.getInstance();
    private final Identifier id = Identifier.fromNamespaceAndPath(CustomPaintingsMod.MOD_ID,
            "draft_preview_" + IDS.incrementAndGet());
    private final Object lock = new Object();
    private long requested;
    private long uploaded;
    private DynamicTexture texture;
    private int width;
    private int height;
    private boolean loading;
    private String error;
    private boolean closed;

    public void request(PaintingEntry entry, byte[] png) {
        long generation;
        synchronized (lock) {
            if (closed) {
                return;
            }
            generation = ++requested;
            loading = true;
            error = null;
        }
        CompletableFuture.supplyAsync(() -> {
            try {
                return compose(entry, png);
            } catch (java.io.IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }, Util.ioPool())
                .whenCompleteAsync((pixels, failure) -> {
                    synchronized (lock) {
                        if (closed || generation != requested) {
                            return;
                        }
                        loading = false;
                        if (failure != null) {
                            error = failure.getCause() == null ? failure.getMessage() : failure.getCause().getMessage();
                            CustomPaintingsMod.LOGGER.warn("Could not preview draft for slot {}", entry.slot(), failure);
                            return;
                        }
                        try {
                            DynamicTexture replacement = new DynamicTexture(id::toString, toNativeImage(pixels));
                            texture = replacement;
                            width = pixels.width();
                            height = pixels.height();
                            uploaded = generation;
                            // Registering the same id releases the previous texture exactly once.
                            minecraft.getTextureManager().register(id, replacement);
                        } catch (RuntimeException exception) {
                            error = exception.getMessage();
                            CustomPaintingsMod.LOGGER.warn("Could not upload draft preview for slot {}", entry.slot(), exception);
                        }
                    }
                }, minecraft);
    }

    private static ImageProcessor.Pixels compose(PaintingEntry entry, byte[] png) throws java.io.IOException {
        BufferedImage source = ImageProcessor.readBytes(png, entry.imageFile());
        int scale = Math.max(1, MAX_PREVIEW_SIDE / Math.max(entry.width(), entry.height()));
        return ImageProcessor.compose(source, entry.width() * scale, entry.height() * scale, entry.fit());
    }

    private static NativeImage toNativeImage(ImageProcessor.Pixels pixels) {
        // Reuse the same conversion as the atlas path without exposing its private helper.
        NativeImage image = new NativeImage(pixels.width(), pixels.height(), false);
        try {
            int[] argb = pixels.argb();
            for (int y = 0; y < pixels.height(); y++) {
                for (int x = 0; x < pixels.width(); x++) {
                    image.setPixel(x, y, argb[y * pixels.width() + x]);
                }
            }
            return image;
        } catch (RuntimeException exception) {
            image.close();
            throw exception;
        }
    }

    /** Draws a checkerboard behind the draft so transparent pixels and the real aspect ratio are visible. */
    public void draw(GuiGraphicsExtractor graphics, int x, int y, int boxWidth, int boxHeight) {
        graphics.fill(x, y, x + boxWidth, y + boxHeight, 0xFF15151B);
        int cell = 8;
        for (int cy = 0; cy < boxHeight; cy += cell) {
            for (int cx = 0; cx < boxWidth; cx += cell) {
                boolean dark = ((cx / cell) + (cy / cell)) % 2 == 0;
                graphics.fill(x + cx, y + cy, Math.min(x + boxWidth, x + cx + cell),
                        Math.min(y + boxHeight, y + cy + cell), dark ? 0xFF2A2A33 : 0xFF3A3A45);
            }
        }
        DynamicTexture current;
        int textureWidth;
        int textureHeight;
        synchronized (lock) {
            current = texture;
            textureWidth = width;
            textureHeight = height;
        }
        if (current != null && textureWidth > 0 && textureHeight > 0) {
            double scale = Math.min((double) boxWidth / textureWidth, (double) boxHeight / textureHeight);
            int drawWidth = Math.max(1, (int) Math.round(textureWidth * scale));
            int drawHeight = Math.max(1, (int) Math.round(textureHeight * scale));
            int drawX = x + (boxWidth - drawWidth) / 2;
            int drawY = y + (boxHeight - drawHeight) / 2;
            graphics.blit(RenderPipelines.GUI_TEXTURED, id, drawX, drawY, 0.0F, 0.0F,
                    drawWidth, drawHeight, textureWidth, textureHeight, textureWidth, textureHeight);
        }
        graphics.outline(x, y, boxWidth, boxHeight, 0xFF6E6289);
    }

    public boolean ready() {
        synchronized (lock) {
            return texture != null && uploaded == requested && !loading;
        }
    }

    public boolean loading() {
        synchronized (lock) {
            return loading;
        }
    }

    public String error() {
        synchronized (lock) {
            return error;
        }
    }

    public int textureWidth() {
        synchronized (lock) {
            return width;
        }
    }

    public int textureHeight() {
        synchronized (lock) {
            return height;
        }
    }

    @Override
    public void close() {
        DynamicTexture previous;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            previous = texture;
            texture = null;
        }
        if (previous != null) {
            minecraft.getTextureManager().release(id);
        }
    }
}
