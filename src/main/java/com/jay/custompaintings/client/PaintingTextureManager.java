package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.image.ImageProcessor;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingRuntime;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Prepares custom painting/item atlas sprites from the synchronized library and registers GUI previews. */
public final class PaintingTextureManager {
    private final ClientPaintingLibrary library;
    private final Path directory;
    private final AtomicLong generation = new AtomicLong();
    private final Map<Integer, Identifier> previewTextures = new HashMap<>();
    private volatile Snapshot current = new Snapshot(List.of(), null, 0);
    private volatile Snapshot pending = current;

    public PaintingTextureManager(ClientPaintingLibrary library, Path directory) {
        this.library = library;
        this.directory = directory;
    }

    public record LoadedImage(PaintingEntry entry, ImageProcessor.Pixels preview, String error) {
        public boolean successful() {
            return error == null;
        }
    }

    public record Snapshot(List<LoadedImage> images, String error, long generation) {
        public Snapshot {
            images = List.copyOf(images);
        }

        public long loadedCount() {
            return images.stream().filter(LoadedImage::successful).count();
        }
    }

    public Path directory() {
        return directory;
    }

    public Snapshot snapshot() {
        return current;
    }

    public Identifier previewTexture(int slot) {
        return previewTextures.get(slot);
    }

    /** Called by SpriteLoaderMixin for both the painting and item atlases before stitching. */
    public synchronized List<SpriteContents> prepareSprites(List<SpriteContents> originals, Identifier atlasId) {
        boolean paintings = atlasId.equals(AtlasIds.PAINTINGS);
        boolean items = atlasId.equals(AtlasIds.ITEMS);
        if (!paintings && !items) {
            return originals;
        }
        List<LoadedImage> loaded = new ArrayList<>();
        Map<String, SpriteContents> byName = new LinkedHashMap<>();
        for (SpriteContents original : originals) {
            byName.put(original.name().toString(), original);
        }
        for (PaintingEntry entry : library.library().entries()) {
            byte[] png = library.image(entry.slot()).orElse(null);
            if (png == null) {
                loaded.add(new LoadedImage(entry, null, "Image has not synchronized yet"));
                continue;
            }
            try {
                BufferedImage source = ImageProcessor.readBytes(png, entry.imageFile());
                ImageProcessor.Pixels full = ImageProcessor.resize(source, entry.textureWidth(), entry.textureHeight());
                ImageProcessor.Pixels preview = ImageProcessor.resize(source, 96, 96);
                loaded.add(new LoadedImage(entry, preview, null));
                Identifier spriteId = paintings
                        ? PaintingRuntime.variantAssetId(entry.slot(), entry.width(), entry.height())
                        : PaintingRuntime.itemTextureId(entry.slot());
                ImageProcessor.Pixels pixels = paintings ? full : preview;
                String key = spriteId.toString();
                SpriteContents previous = byName.get(key);
                NativeImage image = toNativeImage(pixels);
                SpriteContents contents;
                try {
                    contents = new SpriteContents(spriteId, new FrameSize(pixels.width(), pixels.height()), image);
                } catch (RuntimeException exception) {
                    image.close();
                    throw exception;
                }
                byName.put(key, contents);
                if (previous != null) {
                    previous.close();
                }
            } catch (IOException | RuntimeException exception) {
                String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                loaded.add(new LoadedImage(entry, null, message));
                CustomPaintingsMod.LOGGER.warn("Skipping invalid custom painting slot {}: {}", entry.slot(), message);
            }
        }
        if (paintings) {
            pending = new Snapshot(loaded, null, generation.incrementAndGet());
        }
        return List.copyOf(byName.values());
    }

    /** Runs after atlas preparation, on Minecraft's client/render thread. */
    public void installPrepared() {
        Snapshot prepared = pending;
        releasePreviewTextures();
        for (LoadedImage image : prepared.images()) {
            if (!image.successful()) {
                continue;
            }
            Identifier id = CustomPaintingsMod.id("preview/slot_" + image.entry().slot());
            NativeImage nativeImage = toNativeImage(image.preview());
            try {
                DynamicTexture texture = new DynamicTexture(() -> "Custom Paintings slot " + image.entry().slot(), nativeImage);
                Minecraft.getInstance().getTextureManager().register(id, texture);
                previewTextures.put(image.entry().slot(), id);
            } catch (RuntimeException exception) {
                nativeImage.close();
                CustomPaintingsMod.LOGGER.warn("Could not register preview for slot {}", image.entry().slot(), exception);
            }
        }
        String atlasError = verifyAtlasSprites(prepared);
        current = new Snapshot(prepared.images(), atlasError, prepared.generation());
        if (atlasError != null) {
            CustomPaintingsMod.LOGGER.error("Custom painting atlas verification failed: {}", atlasError);
        } else if (!prepared.images().isEmpty()) {
            CustomPaintingsMod.LOGGER.info("Installed and verified {} custom painting/item sprite(s)", prepared.loadedCount());
        }
    }

    /** Detects a missing atlas entry instead of silently rendering Minecraft's checkerboard. */
    private String verifyAtlasSprites(Snapshot prepared) {
        var atlases = Minecraft.getInstance().getAtlasManager();
        var paintings = atlases.getAtlasOrThrow(AtlasIds.PAINTINGS);
        var items = atlases.getAtlasOrThrow(AtlasIds.ITEMS);
        List<String> missing = new ArrayList<>();
        for (LoadedImage image : prepared.images()) {
            if (!image.successful()) {
                continue;
            }
            PaintingEntry entry = image.entry();
            Identifier paintingId = PaintingRuntime.variantAssetId(entry.slot(), entry.width(), entry.height());
            Identifier itemId = PaintingRuntime.itemTextureId(entry.slot());
            if (paintings.getSprite(paintingId) == paintings.missingSprite()) {
                missing.add(paintingId.toString());
            }
            if (items.getSprite(itemId) == items.missingSprite()) {
                missing.add(itemId.toString());
            }
        }
        return missing.isEmpty() ? null : "Missing atlas sprites: " + String.join(", ", missing);
    }

    public void close() {
        releasePreviewTextures();
    }

    private void releasePreviewTextures() {
        var textures = Minecraft.getInstance().getTextureManager();
        previewTextures.values().forEach(textures::release);
        previewTextures.clear();
    }

    private static NativeImage toNativeImage(ImageProcessor.Pixels pixels) {
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
}
