package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.image.ImageAssignment;
import com.jay.custompaintings.image.ImageProcessor;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.Identifier;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Loads atlas pixels on a resource worker, then registers GPU textures on the client thread.
 * DynamicTexture is the official 26.3 name of the class formerly called
 * NativeImageBackedTexture in Yarn. The atlas hook and these previews use identical pixels.
 */
public final class PaintingTextureManager {
    private final Path directory;
    private final AtomicLong generation = new AtomicLong();
    private final Map<String, Identifier> previewTextures = new HashMap<>();
    private volatile Snapshot current = new Snapshot(List.of(), null, 0);
    private volatile Snapshot pending = current;

    public PaintingTextureManager(Path directory) {
        this.directory = directory;
    }

    public record Replacement(String targetId, ImageProcessor.Pixels pixels) {
    }

    public record LoadedImage(String fileName, List<Replacement> replacements,
                              ImageProcessor.Pixels preview, String error) {
        public LoadedImage {
            replacements = List.copyOf(replacements);
        }

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

        public long assignedCount() {
            return images.stream().filter(image -> !image.replacements().isEmpty()).count();
        }
    }

    public Path directory() {
        return directory;
    }

    public Snapshot snapshot() {
        return current;
    }

    public Identifier previewTexture(String fileName) {
        return previewTextures.get(fileName);
    }

    /** Called by SpriteLoaderMixin before the painting atlas is stitched. */
    public synchronized List<SpriteContents> prepareSprites(List<SpriteContents> originals) {
        List<SpriteContents> targets = originals.stream()
                .filter(sprite -> sprite.name().getNamespace().equals(Identifier.DEFAULT_NAMESPACE))
                .filter(sprite -> !sprite.name().getPath().equals("back") && !sprite.name().getPath().equals("missingno"))
                .sorted(Comparator.comparing(sprite -> sprite.name().toString()))
                .toList();
        List<ImageAssignment.Target> dimensions = targets.stream()
                .map(sprite -> new ImageAssignment.Target(sprite.name().toString(), sprite.width(), sprite.height()))
                .toList();

        List<String> files;
        try {
            Files.createDirectories(directory);
            try (Stream<Path> entries = Files.list(directory)) {
                files = entries.map(path -> path.getFileName().toString())
                        .filter(ImageProcessor::isSupported)
                        .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
                        .toList();
            }
        } catch (IOException exception) {
            CustomPaintingsMod.LOGGER.error("Cannot scan {}", directory, exception);
            pending = new Snapshot(List.of(), "Cannot open image folder: " + exception.getMessage(), generation.incrementAndGet());
            return originals;
        }

        Map<String, List<ImageAssignment.Assignment>> assignments = ImageAssignment.assign(files, dimensions);
        List<LoadedImage> loaded = new ArrayList<>();
        Map<String, Replacement> replacements = new HashMap<>();
        for (String fileName : files) {
            List<ImageAssignment.Assignment> assigned = assignments.getOrDefault(fileName, List.of());
            try {
                BufferedImage source = ImageProcessor.read(directory.resolve(fileName));
                Map<String, ImageProcessor.Pixels> sizes = new HashMap<>();
                List<Replacement> imageReplacements = new ArrayList<>();
                for (ImageAssignment.Assignment assignment : assigned) {
                    String key = assignment.width() + "x" + assignment.height();
                    ImageProcessor.Pixels pixels = sizes.computeIfAbsent(key,
                            ignored -> ImageProcessor.resize(source, assignment.width(), assignment.height()));
                    Replacement replacement = new Replacement(assignment.targetId(), pixels);
                    imageReplacements.add(replacement);
                    replacements.put(assignment.targetId(), replacement);
                }
                ImageProcessor.Pixels preview = imageReplacements.isEmpty()
                        ? ImageProcessor.resize(source, 64, 64)
                        : imageReplacements.getFirst().pixels();
                loaded.add(new LoadedImage(fileName, imageReplacements, preview, null));
            } catch (IOException | RuntimeException exception) {
                String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                loaded.add(new LoadedImage(fileName, List.of(), null, message));
                CustomPaintingsMod.LOGGER.warn("Skipping invalid image {}: {}", fileName, message);
            }
        }

        pending = new Snapshot(loaded, null, generation.incrementAndGet());
        if (files.isEmpty() || replacements.isEmpty()) {
            return originals;
        }

        Map<String, SpriteContents> byName = new LinkedHashMap<>();
        for (SpriteContents original : originals) {
            Replacement replacement = replacements.get(original.name().toString());
            if (replacement == null) {
                byName.put(original.name().toString(), original);
                continue;
            }
            ImageProcessor.Pixels pixels = replacement.pixels();
            NativeImage image = toNativeImage(pixels);
            SpriteContents contents;
            try {
                contents = new SpriteContents(original.name(), new FrameSize(pixels.width(), pixels.height()), image);
            } catch (RuntimeException exception) {
                image.close();
                throw exception;
            }
            byName.put(original.name().toString(), contents);
            // The old contents are no longer referenced by the replacement atlas.
            original.close();
        }
        CustomPaintingsMod.LOGGER.info("Loaded {} image(s); replacing {} painting canvas(es)", loaded.size(), replacements.size());
        return List.copyOf(byName.values());
    }

    /** Runs after atlas preparation, on Minecraft's client/render thread. */
    public void installPrepared() {
        Snapshot prepared = pending;
        releasePreviewTextures();
        int index = 0;
        for (LoadedImage image : prepared.images()) {
            if (!image.successful()) {
                continue;
            }
            Identifier id = CustomPaintingsMod.id("loaded/" + index++);
            ImageProcessor.Pixels pixels = image.preview();
            NativeImage nativeImage = toNativeImage(pixels);
            try {
                DynamicTexture texture = new DynamicTexture(() -> "Custom Paintings: " + image.fileName(), nativeImage);
                Minecraft.getInstance().getTextureManager().register(id, texture);
                previewTextures.put(image.fileName(), id);
            } catch (RuntimeException exception) {
                // register() owns the image on success; before that we must free it.
                nativeImage.close();
                CustomPaintingsMod.LOGGER.warn("Could not register a preview for {}", image.fileName(), exception);
            }
        }
        current = prepared;
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
                    // 26.3 setPixel accepts ARGB and handles native byte-order conversion.
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
