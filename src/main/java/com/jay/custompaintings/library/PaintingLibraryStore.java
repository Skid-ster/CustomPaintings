package com.jay.custompaintings.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jay.custompaintings.image.ImageFit;
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
import java.util.Objects;

/**
 * Atomic JSON + canonical master-PNG storage. The server is authoritative; clients receive
 * aspect-preserving master images and compose the final canvas locally from metadata, so
 * changing size/fit never re-encodes or degrades the stored artwork.
 */
public final class PaintingLibraryStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_MASTER_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MASTER_SIDE = 4096;
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
                // v1.2.x stored an already-stretched canvas and had no fit metadata.
                ImageFit fit = object.has("fit") ? ImageFit.fromId(object.get("fit").getAsString()) : ImageFit.STRETCH;
                PaintingEntry entry = new PaintingEntry(
                        object.get("slot").getAsInt(), object.get("name").getAsString(),
                        object.get("width").getAsInt(), object.get("height").getAsInt(), fit,
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
                byte[] png = encodePng(ImageProcessor.prepareMaster(image));
                PaintingSizes.Size size = PaintingSizes.auto(image.getWidth(), image.getHeight());
                PaintingEntry entry = writeImage(slot, name, size.width(), size.height(), ImageFit.FIT,
                        png, migrated.recipeFor(slot));
                migrated = migrated.withAdded(entry);
            } catch (IOException | RuntimeException exception) {
                // Keep migrating valid files; invalid legacy images are left untouched.
            }
        }
        save(migrated);
    }

    public synchronized PaintingEntry add(String name, int width, int height, byte[] png) throws IOException {
        return add(name, width, height, ImageFit.FIT, png);
    }

    public synchronized PaintingEntry add(String name, int width, int height, ImageFit fit, byte[] png) throws IOException {
        if (library.count() == 0 && library.recipeSeed() == 0) {
            library = new PaintingLibrary(library.revision(), new SecureRandom().nextLong(), List.of());
        }
        int slot = library.firstFreeSlot().orElseThrow(() -> new IOException("All 10 custom painting slots are full"));
        String recipe = library.recipeFor(slot);
        PaintingEntry entry = writeImage(slot, name, width, height, fit, png, recipe);
        save(library.withAdded(entry));
        return entry;
    }

    public synchronized void rename(int slot, String name) throws IOException {
        save(library.withRenamed(slot, name));
    }

    /** Metadata-only resize: the master PNG and its digest are preserved. */
    public synchronized void resize(int slot, int width, int height) throws IOException {
        PaintingEntry entry = library.get(slot).orElseThrow(() -> new IOException("Empty painting slot"));
        adjust(slot, entry.name(), width, height, entry.fit());
    }

    public synchronized void setFit(int slot, ImageFit fit) throws IOException {
        PaintingEntry entry = library.get(slot).orElseThrow(() -> new IOException("Empty painting slot"));
        adjust(slot, entry.name(), entry.width(), entry.height(), fit);
    }

    /** Atomic name/size/fit edit used by the in-game editor. */
    public synchronized void adjust(int slot, String name, int width, int height, ImageFit fit) throws IOException {
        PaintingEntry entry = library.get(slot).orElseThrow(() -> new IOException("Empty painting slot"));
        Objects.requireNonNull(fit, "fit");
        if (!PaintingSizes.isSupported(width, height)) {
            throw new IOException("Unsupported painting size");
        }
        verifyImage(entry);
        PaintingEntry adjusted = new PaintingEntry(slot, name, width, height, fit,
                entry.imageFile(), entry.sha256(), entry.recipeId());
        save(library.withReplaced(adjusted));
    }

    public synchronized void remove(int slot) throws IOException {
        PaintingEntry entry = library.get(slot).orElseThrow(() -> new IOException("Empty painting slot"));
        save(library.without(slot));
        Files.deleteIfExists(imagePath(entry));
    }

    /** Upload several prepared master PNGs as one authoritative mutation and one manifest save. */
    public synchronized List<PaintingEntry> addBatch(List<Upload> uploads) throws IOException {
        Objects.requireNonNull(uploads, "uploads");
        if (uploads.isEmpty()) {
            throw new IOException("No paintings to add");
        }
        if (uploads.size() > PaintingEntry.MAX_PAINTINGS || uploads.size() > library.freeSlots()) {
            throw new IOException("Only " + library.freeSlots() + " free painting slot(s) remain");
        }
        // Validate the whole batch before touching the image directory so a bad file cannot
        // leave a half-applied upload behind.
        for (Upload upload : uploads) {
            Objects.requireNonNull(upload, "upload");
            if (!PaintingSizes.isSupported(upload.width(), upload.height())) {
                throw new IOException("Unsupported painting size");
            }
            Objects.requireNonNull(upload.fit(), "fit");
            validateMaster(upload.png());
        }
        if (library.count() == 0 && library.recipeSeed() == 0) {
            library = new PaintingLibrary(library.revision(), new SecureRandom().nextLong(), List.of());
        }
        List<PaintingEntry> added = new ArrayList<>();
        List<PaintingEntry> entries = new ArrayList<>(library.entries());
        try {
            for (Upload upload : uploads) {
                int slot = firstFreeSlot(entries);
                PaintingEntry entry = writeImage(slot, upload.name(), upload.width(), upload.height(),
                        upload.fit(), upload.png(), library.recipeFor(slot));
                added.add(entry);
                entries.add(entry);
            }
            save(new PaintingLibrary(library.revision() + 1, library.recipeSeed(), entries));
            return List.copyOf(added);
        } catch (IOException | RuntimeException exception) {
            for (PaintingEntry entry : added) {
                try {
                    Files.deleteIfExists(imagePath(entry));
                } catch (IOException cleanup) {
                    exception.addSuppressed(cleanup);
                }
            }
            throw exception;
        }
    }

    /**
     * Apply a whole draft batch atomically: validate every edit/removal against the expected
     * revision, save the manifest once, then delete removed masters. Metadata edits keep the
     * original PNG bytes and digest untouched.
     */
    public synchronized void applyBatch(long expectedRevision, List<PaintingEdit> edits, List<Integer> removals)
            throws IOException {
        Objects.requireNonNull(edits, "edits");
        Objects.requireNonNull(removals, "removals");
        if (edits.isEmpty() && removals.isEmpty()) {
            throw new IOException("No staged changes to apply");
        }
        if (edits.size() + removals.size() > PaintingEntry.MAX_PAINTINGS) {
            throw new IOException("Too many staged changes");
        }
        if (library.revision() != expectedRevision) {
            throw new IOException("The painting library changed since these edits were staged; discard and review again");
        }
        java.util.Set<Integer> touched = new java.util.HashSet<>();
        for (PaintingEdit edit : edits) {
            Objects.requireNonNull(edit, "edit");
            if (!touched.add(edit.slot())) {
                throw new IOException("Duplicate staged edit for slot " + edit.slot());
            }
            PaintingEntry entry = library.get(edit.slot())
                    .orElseThrow(() -> new IOException("Empty painting slot " + edit.slot()));
            verifyImage(entry);
        }
        for (int slot : removals) {
            if (!touched.add(slot)) {
                throw new IOException("Slot " + slot + " is both edited and removed");
            }
            library.get(slot).orElseThrow(() -> new IOException("Empty painting slot " + slot));
        }
        List<PaintingEntry> entries = new ArrayList<>();
        for (PaintingEntry entry : library.entries()) {
            if (removals.contains(entry.slot())) {
                continue;
            }
            PaintingEntry replacement = entry;
            for (PaintingEdit edit : edits) {
                if (edit.slot() == entry.slot()) {
                    replacement = edit.applyTo(entry);
                    break;
                }
            }
            entries.add(replacement);
        }
        List<PaintingEntry> removedEntries = library.entries().stream()
                .filter(entry -> removals.contains(entry.slot())).toList();
        save(new PaintingLibrary(library.revision() + 1, library.recipeSeed(), entries));
        for (PaintingEntry entry : removedEntries) {
            Files.deleteIfExists(imagePath(entry));
        }
    }

    private static int firstFreeSlot(List<PaintingEntry> entries) throws IOException {
        for (int slot = 0; slot < PaintingEntry.MAX_PAINTINGS; slot++) {
            int candidate = slot;
            if (entries.stream().noneMatch(entry -> entry.slot() == candidate)) {
                return slot;
            }
        }
        throw new IOException("All 10 custom painting slots are full");
    }

    /** One prepared image in an atomic upload batch. */
    public record Upload(String name, int width, int height, ImageFit fit, byte[] png) {
    }

    private PaintingEntry writeImage(int slot, String name, int width, int height, ImageFit fit,
                                     byte[] png, String recipe) throws IOException {
        if (!PaintingSizes.isSupported(width, height)) {
            throw new IOException("Unsupported painting size");
        }
        Objects.requireNonNull(fit, "fit");
        validateMaster(png);
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
        return new PaintingEntry(slot, name, width, height, fit, target.getFileName().toString(), sha256(png), recipe);
    }

    private void verifyImage(PaintingEntry entry) throws IOException {
        Path path = imagePath(entry);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("Missing image for slot " + entry.slot());
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > MAX_MASTER_BYTES) {
            throw new IOException("Image exceeds the master size limit for slot " + entry.slot());
        }
        if (!sha256(bytes).equals(entry.sha256())) {
            throw new IOException("Image digest mismatch for slot " + entry.slot());
        }
        BufferedImage image = ImageProcessor.readBytes(bytes, entry.imageFile());
        validateMasterDimensions(image, entry.slot());
    }

    private static void validateMaster(byte[] png) throws IOException {
        if (png == null || png.length == 0 || png.length > MAX_MASTER_BYTES) {
            throw new IOException("Invalid master image size");
        }
        BufferedImage image = ImageProcessor.readBytes(png, "slot.png");
        validateMasterDimensions(image, -1);
    }

    private static void validateMasterDimensions(BufferedImage image, int slot) throws IOException {
        if (image.getWidth() <= 0 || image.getHeight() <= 0
                || image.getWidth() > MAX_MASTER_SIDE || image.getHeight() > MAX_MASTER_SIDE
                || (long) image.getWidth() * image.getHeight() > ImageProcessor.MAX_OUTPUT_PIXELS) {
            throw new IOException("Master image dimensions are outside the safe limit"
                    + (slot >= 0 ? " for slot " + slot : ""));
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
            object.addProperty("fit", entry.fit().id());
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
