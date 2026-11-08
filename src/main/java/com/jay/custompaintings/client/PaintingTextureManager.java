package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.image.ImageProcessor;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingRuntime;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Uploads artwork as standalone dynamic textures instead of stitching an atlas.
 *
 * <p>This is what makes the library unbounded: atlases have a fixed maximum size, but dynamic
 * textures are allocated per painting and evicted least-recently-used, so ten thousand
 * paintings cost no more GPU memory than the ones actually on screen. Composition happens on
 * a worker thread; only the small GPU upload touches the render thread.</p>
 */
public final class PaintingTextureManager {
    /** Resident GPU textures per kind; everything else is recomposed on demand. */
    private static final int MAX_RESIDENT = 128;
    private static final int PREVIEW_SIDE = 128;

    private final ClientPaintingLibrary library;
    private final Map<String, Entry> resident = new LinkedHashMap<>(16, 0.75F, true);
    private final Set<String> loading = new HashSet<>();
    private volatile long generation;

    public PaintingTextureManager(ClientPaintingLibrary library) {
        this.library = library;
    }

    private record Key(int id, boolean preview, String sha256, int width, int height, String fit) {
        private String cacheKey() {
            return (preview ? "p" : "a") + id + ":" + sha256 + ":" + width + "x" + height + ":" + fit;
        }

        private Identifier textureId() {
            return preview ? PaintingRuntime.previewTexture(id) : PaintingRuntime.artworkTexture(id);
        }
    }

    private static final class Entry {
        private final Identifier textureId;
        private final int width;
        private final int height;

        private Entry(Identifier textureId, int width, int height) {
            this.textureId = textureId;
            this.width = width;
            this.height = height;
        }
    }

    /** A ready-to-draw texture, or null while the artwork is still being fetched/composed. */
    public record Texture(Identifier id, int width, int height) {
    }

    public long generation() {
        return generation;
    }

    /** Number of textures currently resident on the GPU; used by the in-game regression test. */
    public synchronized int uploadedCount() {
        return resident.size();
    }

    public Texture artwork(int paintingId) {
        return resolve(paintingId, false);
    }

    public Texture preview(int paintingId) {
        return resolve(paintingId, true);
    }

    private synchronized Texture resolve(int paintingId, boolean preview) {
        PaintingEntry entry = library.entry(paintingId).orElse(null);
        if (entry == null) {
            return null;
        }
        int width = preview ? PREVIEW_SIDE : entry.textureWidth();
        int height = preview ? PREVIEW_SIDE : entry.textureHeight();
        Key key = new Key(paintingId, preview, entry.sha256(), width, height, entry.fit().id());
        String cacheKey = key.cacheKey();
        Entry existing = resident.get(cacheKey);
        if (existing != null) {
            return new Texture(existing.textureId, existing.width, existing.height);
        }
        // Fetches from disk/server on first use; returns empty until the bytes arrive.
        byte[] png = library.imageOrRequest(paintingId).orElse(null);
        if (png == null || loading.contains(cacheKey)) {
            return null;
        }
        loading.add(cacheKey);
        Identifier textureId = key.textureId();
        String imageFile = entry.imageFile();
        com.jay.custompaintings.image.ImageFit fit = entry.fit();
        CompletableFuture.runAsync(() -> {
            // Decode and resample off the render thread; only the GPU upload comes back.
            BufferedImage source;
            try {
                source = ImageProcessor.readBytes(png, imageFile);
            } catch (IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
            ImageProcessor.Pixels pixels = ImageProcessor.compose(source, width, height, fit);
            NativeImage image = toNativeImage(pixels);
            Minecraft.getInstance().execute(() -> {
                try {
                    DynamicTexture texture = new DynamicTexture(textureId::toString, image);
                    // Registering the same id releases the previous texture exactly once.
                    Minecraft.getInstance().getTextureManager().register(textureId, texture);
                    synchronized (PaintingTextureManager.this) {
                        resident.put(cacheKey, new Entry(textureId, width, height));
                        loading.remove(cacheKey);
                        generation++;
                        evict();
                    }
                } catch (RuntimeException exception) {
                    image.close();
                    synchronized (PaintingTextureManager.this) {
                        loading.remove(cacheKey);
                    }
                    CustomPaintingsMod.LOGGER.warn("Could not upload texture for painting {}", paintingId, exception);
                }
            });
        }, Util.ioPool()).exceptionally(error -> {
            synchronized (PaintingTextureManager.this) {
                loading.remove(cacheKey);
            }
            CustomPaintingsMod.LOGGER.warn("Could not prepare texture for painting {}", paintingId, error);
            return null;
        });
        return null;
    }

    /** Drops the least-recently-used textures so GPU use stays bounded. */
    private void evict() {
        while (resident.size() > MAX_RESIDENT) {
            Iterator<Map.Entry<String, Entry>> iterator = resident.entrySet().iterator();
            if (!iterator.hasNext()) {
                return;
            }
            Entry eldest = iterator.next().getValue();
            iterator.remove();
            // Another cache key may still reference the same id; only release when none do.
            boolean stillUsed = resident.values().stream().anyMatch(value -> value.textureId.equals(eldest.textureId));
            if (!stillUsed) {
                Minecraft.getInstance().getTextureManager().release(eldest.textureId);
            }
        }
    }

    /**
     * Releases GPU textures but keeps the cached master bytes. Used when fresh pixels arrive,
     * so the next frame recomposes from them instead of re-requesting them from the server.
     */
    public synchronized void releaseTextures(int paintingId) {
        release(paintingId);
        generation++;
    }

    /** Forget a painting's textures and bytes after its pixels or canvas changed. */
    public synchronized void invalidate(int paintingId) {
        release(paintingId);
        library.invalidate(paintingId);
        generation++;
    }

    private void release(int paintingId) {
        Iterator<Map.Entry<String, Entry>> iterator = resident.entrySet().iterator();
        Set<Identifier> released = new HashSet<>();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (entry.textureId.equals(PaintingRuntime.artworkTexture(paintingId))
                    || entry.textureId.equals(PaintingRuntime.previewTexture(paintingId))) {
                released.add(entry.textureId);
                iterator.remove();
            }
        }
        released.forEach(Minecraft.getInstance().getTextureManager()::release);
    }

    public synchronized void close() {
        Set<Identifier> ids = new HashSet<>();
        resident.values().forEach(entry -> ids.add(entry.textureId));
        resident.clear();
        loading.clear();
        ids.forEach(Minecraft.getInstance().getTextureManager()::release);
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
