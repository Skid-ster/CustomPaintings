package com.jay.custompaintings.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jay.custompaintings.image.ImageProcessor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Atomic JSON + canonical PNG storage. The server is authoritative; clients receive synced copies. */
public final class PaintingLibraryStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Path root;
    private final Path images;
    private final Path manifest;
    private PaintingLibrary library;

    public PaintingLibraryStore(Path root) {
        this.root = root;
        this.images = root.resolve("images");
        this.manifest = root.resolve("library.json");
        this.library = PaintingLibrary.EMPTY;
    }

    public synchronized PaintingLibrary library() {
        return library;
    }

    public Path imagesDirectory() {
        return images;
    }

    public Path imagePath(PaintingEntry entry) {
        return images.resolve(entry.imageFile());
    }

    public synchronized void load() throws IOException {
        Files.createDirectories(images);
        if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            library = PaintingLibrary.EMPTY;
            migrateLegacy();
            return;
        }
        if (Files.isSymbolicLink(manifest) || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("library.json must be a regular, non-symlink file");
        }
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            long revision = json.get("revision").getAsLong();
            long seed = json.get("recipe_seed").getAsLong();
            List<PaintingEntry> entries = new ArrayList<>();
            JsonArray array = json.getAsJsonArray("entries");
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                PaintingEntry entry = new PaintingEntry(
                        object.get("slot").getAsInt(), object.get("name").getAsString(),
                        object.get("width").getAsInt(), object.get("height").getAsInt(),
                        object.get("image").getAsString(), object.get("sha256").getAsString(),
                        object.get("recipe").getAsString());
                verifyImage(entry);
                entries.add(entry);
            }
            library = new PaintingLibrary(revision, seed, entries);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid custom painting library: " + exception.getMessage(), exception);
        }
    }

    private void migrateLegacy() throws IOException {
        // v1.1 stored loose replacement images in images/ without a manifest.
        List<Path> legacy;
        try (var stream = Files.list(images)) {
            legacy = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> ImageProcessor.isSupported(path.getFileName().toString()))
                    .sorted().limit(PaintingEntry.MAX_PAINTINGS).toList();
        }
        if (legacy.isEmpty()) {
            save(PaintingLibrary.EMPTY);
            return;
        }
        PaintingLibrary migrated = new PaintingLibrary(0, new SecureRandom().nextLong(), List.of());
        for (Path source : legacy) {
            String fileName = source.getFileName().toString();
            int slot = migrated.firstFreeSlot().orElseThrow();
            String name = fileName.replaceFirst("\\.(png|jpg|jpeg)$", "").replace('_', ' ').strip();
            if (name.isEmpty()) {
                name = "Custom Painting";
            }
            if (name.length() > 48) {
                name = name.substring(0, 48);
            }
            try {
                BufferedImage image = ImageProcessor.read(source);
                byte[] png = encodePng(ImageProcessor.resize(image,
                        PaintingSizes.DEFAULT.width() * PaintingEntry.TEXTURE_PIXELS_PER_BLOCK,
                        PaintingSizes.DEFAULT.height() * PaintingEntry.TEXTURE_PIXELS_PER_BLOCK));
                PaintingEntry entry = writeImage(slot, name, PaintingSizes.DEFAULT.width(), PaintingSizes.DEFAULT.height(),
                        png, migrated.recipeFor(slot));
                migrated = migrated.withAdded(entry);
            } catch (IOException | RuntimeException exception) {
                // Keep migrating valid files; invalid legacy images are left untouched.
            }
        }
        save(migrated);
    }

    public synchronized PaintingEntry add(String name, int width, int height, byte[] png) throws IOException {
        if (library.count() == 0 && library.recipeSeed() == 0) {
            library = new PaintingLibrary(library.revision(), new SecureRandom().nextLong(), java.util.List.of());
        }
        int slot = library.firstFreeSlot().orElseThrow(() -> new IOException("All 10 custom painting slots are full"));
        String recipe = library.recipeFor(slot);
        PaintingEntry entry = writeImage(slot, name, width, height, png, recipe);
        save(library.withAdded(entry));
        return entry;
    }

    public synchronized void rename(int slot, String name) throws IOException {
        save(library.withRenamed(slot, name));
    }

    public synchronized void resize(int slot, int width, int height) throws IOException {
        PaintingEntry entry = library.get(slot).orElseThrow(() -> new IOException("Empty painting slot"));
        BufferedImage source = ImageProcessor.read(imagePath(entry));
        byte[] png = encodePng(ImageProcessor.resize(source,
                width * PaintingEntry.TEXTURE_PIXELS_PER_BLOCK, height * PaintingEntry.TEXTURE_PIXELS_PER_BLOCK));
        PaintingEntry updated = writeImage(slot, entry.name(), width, height, png, entry.recipeId());
        List<PaintingEntry> entries = library.entries().stream()
                .map(existing -> existing.slot() == slot ? updated : existing).toList();
        save(new PaintingLibrary(library.revision() + 1, library.recipeSeed(), entries));
    }

    public synchronized void remove(int slot) throws IOException {
        PaintingEntry entry = library.get(slot).orElseThrow(() -> new IOException("Empty painting slot"));
        save(library.without(slot));
        Files.deleteIfExists(imagePath(entry));
    }

    private PaintingEntry writeImage(int slot, String name, int width, int height, byte[] png, String recipe) throws IOException {
        if (!PaintingSizes.isSupported(width, height)) {
            throw new IOException("Unsupported painting size");
        }
        if (png.length == 0 || png.length > ImageProcessor.MAX_FILE_BYTES) {
            throw new IOException("Invalid processed image size");
        }
        BufferedImage decoded = ImageProcessor.readBytes(png, "slot.png");
        if (decoded.getWidth() != width * PaintingEntry.TEXTURE_PIXELS_PER_BLOCK
                || decoded.getHeight() != height * PaintingEntry.TEXTURE_PIXELS_PER_BLOCK) {
            throw new IOException("Processed image dimensions do not match the selected painting size");
        }
        Files.createDirectories(images);
        Path target = images.resolve("slot_" + slot + ".png");
        Path staged = Files.createTempFile(images, ".slot-", ".tmp");
        try {
            Files.write(staged, png);
            try {
                Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
        return new PaintingEntry(slot, name, width, height, target.getFileName().toString(), sha256(png), recipe);
    }

    private void verifyImage(PaintingEntry entry) throws IOException {
        Path path = imagePath(entry);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing image for slot " + entry.slot());
        }
        byte[] bytes = Files.readAllBytes(path);
        if (!sha256(bytes).equals(entry.sha256())) {
            throw new IOException("Image digest mismatch for slot " + entry.slot());
        }
        BufferedImage image = ImageProcessor.readBytes(bytes, entry.imageFile());
        if (image.getWidth() != entry.textureWidth() || image.getHeight() != entry.textureHeight()) {
            throw new IOException("Image dimensions mismatch for slot " + entry.slot());
        }
    }

    private void save(PaintingLibrary updated) throws IOException {
        Files.createDirectories(root);
        JsonObject json = new JsonObject();
        json.addProperty("revision", updated.revision());
        json.addProperty("recipe_seed", updated.recipeSeed());
        JsonArray entries = new JsonArray();
        for (PaintingEntry entry : updated.entries()) {
            JsonObject object = new JsonObject();
            object.addProperty("slot", entry.slot());
            object.addProperty("name", entry.name());
            object.addProperty("width", entry.width());
            object.addProperty("height", entry.height());
            object.addProperty("image", entry.imageFile());
            object.addProperty("sha256", entry.sha256());
            object.addProperty("recipe", entry.recipeId());
            entries.add(object);
        }
        json.add("entries", entries);
        Path staged = Files.createTempFile(root, ".library-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(staged, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
            try {
                Files.move(staged, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(staged, manifest, StandardCopyOption.REPLACE_EXISTING);
            }
            library = updated;
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public static byte[] encodePng(ImageProcessor.Pixels pixels) throws IOException {
        BufferedImage image = new BufferedImage(pixels.width(), pixels.height(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, pixels.width(), pixels.height(), pixels.argb(), 0, pixels.width());
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG encoder unavailable");
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
