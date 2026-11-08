package com.jay.custompaintings.library;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jay.custompaintings.image.ImageFit;
import com.jay.custompaintings.image.ImageProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Atomic JSON + canonical master-PNG storage for an <b>unbounded</b> library. The server is
 * authoritative; clients receive aspect-preserving master images and compose the final canvas
 * locally from metadata, so changing size/fit never re-encodes or degrades the stored artwork.
 *
 * <p>v1.x libraries (ten {@code slot_N} entries) are migrated in place: ids are preserved and
 * master PNGs are renamed to {@code painting_N.png}, so existing worlds keep their artwork.</p>
 */
public final class PaintingLibraryStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("Custom Paintings");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int MAX_MASTER_BYTES = 8 * 1024 * 1024;
    private static final int MAX_MASTER_SIDE = 4096;
    /** Safety valve against a corrupt/hostile manifest, not a user-facing painting cap. */
    private static final int MAX_MANIFEST_ENTRIES = 1_000_000;
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
            boolean hasNextId = json.has("next_id");
            int nextId = hasNextId ? json.get("next_id").getAsInt() : 0;
            JsonArray array = json.getAsJsonArray("entries");
            if (array.size() > MAX_MANIFEST_ENTRIES) {
                throw new IOException("Painting manifest is implausibly large");
            }
            List<PaintingEntry> entries = new ArrayList<>();
            boolean migrated = !hasNextId;
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                // v1.x wrote "slot" and slot_N.png; v2 writes "id" and painting_N.png.
                boolean legacy = !object.has("id");
                int id = legacy ? object.get("slot").getAsInt() : object.get("id").getAsInt();
                ImageFit fit = object.has("fit") ? ImageFit.fromId(object.get("fit").getAsString()) : ImageFit.STRETCH;
                String name = object.get("name").getAsString();
                int width = object.get("width").getAsInt();
                int height = object.get("height").getAsInt();
                String sha = object.get("sha256").getAsString();
                // v1 recipes came from a 10-entry paper+dye catalogue that no longer exists.
                String recipe = object.has("recipe") ? object.get("recipe").getAsString() : PaintingEntry.NO_RECIPE;
                if (!recipe.isEmpty() && !RecipeCatalog.contains(recipe)) {
                    recipe = PaintingEntry.NO_RECIPE;
                    migrated = true;
                }
                if (legacy) {
                    renameLegacyImage(id, object.has("image") ? object.get("image").getAsString() : "slot_" + id + ".png");
                    migrated = true;
                }
                PaintingEntry entry = new PaintingEntry(id, name, width, height, fit,
                        PaintingEntry.imageFileFor(id), sha, recipe);
                verifyImage(entry);
                entries.add(entry);
            }
            if (!hasNextId) {
                nextId = new PaintingLibrary(revision, seed, entries).nextId();
            }
            PaintingLibrary loaded = new PaintingLibrary(revision, seed, entries, nextId);
            if (migrated) {
                // Reassign valid wood/dye recipes to any painting that lost its legacy recipe.
                loaded = reassignRecipes(loaded);
                save(loaded);
                LOGGER.info("Migrated legacy painting library to v2 format ({} paintings)", loaded.count());
            } else {
                library = loaded;
            }
        } catch (RuntimeException exception) {
            throw new IOException("Invalid custom painting library: " + exception.getMessage(), exception);
        }
    }

    /** Give menu-only paintings a free wood/dye recipe when the catalogue has room. */
    private PaintingLibrary reassignRecipes(PaintingLibrary source) {
        List<PaintingEntry> entries = new ArrayList<>();
        PaintingLibrary working = source;
        for (PaintingEntry entry : source.entries()) {
            if (entry.hasRecipe()) {
                entries.add(entry);
                continue;
            }
            String recipe = working.nextRecipe();
            PaintingEntry updated = entry.withRecipe(recipe);
            entries.add(updated);
            working = new PaintingLibrary(source.revision(), source.recipeSeed(), entries, source.nextId());
        }
        return new PaintingLibrary(source.revision(), source.recipeSeed(), entries, source.nextId());
    }

    private void renameLegacyImage(int id, String legacyName) throws IOException {
        Path target = images.resolve(PaintingEntry.imageFileFor(id));
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        // Only accept the canonical legacy name so a hostile manifest cannot move arbitrary files.
        if (!legacyName.matches("slot_[0-9]+\\.png")) {
            throw new IOException("Unexpected legacy image name: " + legacyName);
        }
        Path source = images.resolve(legacyName);
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Missing legacy image " + legacyName);
        }
        Files.copy(source, target);
    }

    private void migrateLegacy() throws IOException {
        // v1.1 stored loose replacement images in images/ without a manifest.
        List<Path> legacy;
        try (var stream = Files.list(images)) {
            legacy = stream.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> ImageProcessor.isSupported(path.getFileName().toString()))
                    .sorted().toList();
        }
        if (legacy.isEmpty()) {
            save(PaintingLibrary.EMPTY);
            return;
        }
        PaintingLibrary migrated = new PaintingLibrary(0, new SecureRandom().nextLong(), List.of());
        for (Path source : legacy) {
            String fileName = source.getFileName().toString();
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
                int id = migrated.nextId();
                PaintingEntry entry = writeImage(id, name, size.width(), size.height(), ImageFit.FIT,
                        png, migrated.nextRecipe());
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
        ensureSeed();
        int id = library.nextId();
        PaintingEntry entry = writeImage(id, name, width, height, fit, png, library.nextRecipe());
        save(library.withAdded(entry));
        return entry;
    }

    private void ensureSeed() {
        if (library.recipeSeed() == 0) {
            library = new PaintingLibrary(library.revision(), new SecureRandom().nextLong(),
                    library.entries(), library.nextId());
        }
    }

    public synchronized void rename(int id, String name) throws IOException {
        save(library.withRenamed(id, name));
    }

    /** Metadata-only resize: the master PNG and its digest are preserved. */
    public synchronized void resize(int id, int width, int height) throws IOException {
        PaintingEntry entry = library.get(id).orElseThrow(() -> new IOException("Unknown painting id"));
        adjust(id, entry.name(), width, height, entry.fit());
    }

    public synchronized void setFit(int id, ImageFit fit) throws IOException {
        PaintingEntry entry = library.get(id).orElseThrow(() -> new IOException("Unknown painting id"));
        adjust(id, entry.name(), entry.width(), entry.height(), fit);
    }

    /** Atomic name/size/fit edit used by the in-game editor. */
    public synchronized void adjust(int id, String name, int width, int height, ImageFit fit) throws IOException {
        PaintingEntry entry = library.get(id).orElseThrow(() -> new IOException("Unknown painting id"));
        Objects.requireNonNull(fit, "fit");
        if (!PaintingSizes.isSupported(width, height)) {
            throw new IOException("Unsupported painting size");
        }
        verifyImage(entry);
        PaintingEntry adjusted = new PaintingEntry(id, name, width, height, fit,
                entry.imageFile(), entry.sha256(), entry.recipeId());
        save(library.withReplaced(adjusted));
    }

    public synchronized void remove(int id) throws IOException {
        PaintingEntry entry = library.get(id).orElseThrow(() -> new IOException("Unknown painting id"));
        save(library.without(id));
        deleteAfterCommit(entry);
    }

    /** Upload several prepared master PNGs as one authoritative mutation and one manifest save. */
    public synchronized List<PaintingEntry> addBatch(List<Upload> uploads) throws IOException {
        Objects.requireNonNull(uploads, "uploads");
        if (uploads.isEmpty()) {
            throw new IOException("No paintings to add");
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
        ensureSeed();
        List<PaintingEntry> added = new ArrayList<>();
        List<PaintingEntry> entries = new ArrayList<>(library.entries());
        try {
            int nextId = library.nextId();
            Set<String> used = new HashSet<>();
            for (PaintingEntry entry : entries) {
                if (entry.hasRecipe()) {
                    used.add(entry.recipeId());
                }
            }
            List<String> order = RecipeCatalog.shuffledIds(library.recipeSeed());
            for (Upload upload : uploads) {
                String recipe = PaintingEntry.NO_RECIPE;
                for (String candidate : order) {
                    if (!used.contains(candidate)) {
                        recipe = candidate;
                        used.add(candidate);
                        break;
                    }
                }
                PaintingEntry entry = writeImage(nextId++, upload.name(), upload.width(), upload.height(),
                        upload.fit(), upload.png(), recipe);
                added.add(entry);
                entries.add(entry);
            }
            save(new PaintingLibrary(library.revision() + 1, library.recipeSeed(), entries, nextId));
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
        if (library.revision() != expectedRevision) {
            throw new IOException("The painting library changed since these edits were staged; discard and review again");
        }
        Set<Integer> touched = new HashSet<>();
        for (PaintingEdit edit : edits) {
            Objects.requireNonNull(edit, "edit");
            if (!touched.add(edit.id())) {
                throw new IOException("Duplicate staged edit for painting " + edit.id());
            }
            PaintingEntry entry = library.get(edit.id())
                    .orElseThrow(() -> new IOException("Unknown painting id " + edit.id()));
            verifyImage(entry);
        }
        for (int id : removals) {
            if (!touched.add(id)) {
                throw new IOException("Painting " + id + " is both edited and removed");
            }
            library.get(id).orElseThrow(() -> new IOException("Unknown painting id " + id));
        }
        Set<Integer> removedIds = new HashSet<>(removals);
        List<PaintingEntry> entries = new ArrayList<>();
        for (PaintingEntry entry : library.entries()) {
            if (removedIds.contains(entry.id())) {
                continue;
            }
            PaintingEntry replacement = entry;
            for (PaintingEdit edit : edits) {
                if (edit.id() == entry.id()) {
                    replacement = edit.applyTo(entry);
                    break;
                }
            }
            entries.add(replacement);
        }
        List<PaintingEntry> removedEntries = library.entries().stream()
                .filter(entry -> removedIds.contains(entry.id())).toList();
        save(new PaintingLibrary(library.revision() + 1, library.recipeSeed(), entries, library.nextId()));
        for (PaintingEntry entry : removedEntries) {
            deleteAfterCommit(entry);
        }
    }

    /** The manifest is already committed; an orphan cleanup failure must not report a failed mutation. */
    private void deleteAfterCommit(PaintingEntry entry) {
        try {
            Files.deleteIfExists(imagePath(entry));
        } catch (IOException exception) {
            LOGGER.warn(
                    "Removed painting {} from the library but could not delete its old master PNG", entry.id(), exception);
        }
    }

    /** One prepared image in an atomic upload batch. */
    public record Upload(String name, int width, int height, ImageFit fit, byte[] png) {
    }

    private PaintingEntry writeImage(int id, String name, int width, int height, ImageFit fit,
                                     byte[] png, String recipe) throws IOException {
        if (!PaintingSizes.isSupported(width, height)) {
            throw new IOException("Unsupported painting size");
        }
        Objects.requireNonNull(fit, "fit");
        validateMaster(png);
        Files.createDirectories(images);
        Path target = images.resolve(PaintingEntry.imageFileFor(id));
        Path staged = Files.createTempFile(images, ".painting-", ".tmp");
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
        return new PaintingEntry(id, name, width, height, fit, target.getFileName().toString(), sha256(png), recipe);
    }

    private void verifyImage(PaintingEntry entry) throws IOException {
        Path path = imagePath(entry);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("Missing image for painting " + entry.id());
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > MAX_MASTER_BYTES) {
            throw new IOException("Image exceeds the master size limit for painting " + entry.id());
        }
        if (!sha256(bytes).equals(entry.sha256())) {
            throw new IOException("Image digest mismatch for painting " + entry.id());
        }
        BufferedImage image = ImageProcessor.readBytes(bytes, entry.imageFile());
        validateMasterDimensions(image, entry.id());
    }

    private static void validateMaster(byte[] png) throws IOException {
        if (png == null || png.length == 0 || png.length > MAX_MASTER_BYTES) {
            throw new IOException("Invalid master image size");
        }
        BufferedImage image = ImageProcessor.readBytes(png, "painting.png");
        validateMasterDimensions(image, -1);
    }

    private static void validateMasterDimensions(BufferedImage image, int id) throws IOException {
        if (image.getWidth() <= 0 || image.getHeight() <= 0
                || image.getWidth() > MAX_MASTER_SIDE || image.getHeight() > MAX_MASTER_SIDE
                || (long) image.getWidth() * image.getHeight() > ImageProcessor.MAX_OUTPUT_PIXELS) {
            throw new IOException("Master image dimensions are outside the safe limit"
                    + (id >= 0 ? " for painting " + id : ""));
        }
    }

    private void save(PaintingLibrary updated) throws IOException {
        Files.createDirectories(root);
        JsonObject json = new JsonObject();
        json.addProperty("format", 2);
        json.addProperty("revision", updated.revision());
        json.addProperty("recipe_seed", updated.recipeSeed());
        json.addProperty("next_id", updated.nextId());
        JsonArray entries = new JsonArray();
        for (PaintingEntry entry : updated.entries()) {
            JsonObject object = new JsonObject();
            object.addProperty("id", entry.id());
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
