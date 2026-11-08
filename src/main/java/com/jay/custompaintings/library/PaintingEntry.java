package com.jay.custompaintings.library;

import com.jay.custompaintings.image.ImageFit;

import java.util.Locale;
import java.util.Objects;

/**
 * One custom painting. Ids are stable, monotonically allocated and <b>unbounded</b>: the
 * library can hold as many paintings as disk and memory allow. A painting keeps its id for
 * life, so placed entities, held items and creative-tab entries survive renames and relogs.
 *
 * <p>{@code recipeId} is optional. The wood/dye crafting catalogue is finite, so once every
 * combination is taken further paintings are menu-only ({@link #NO_RECIPE}).</p>
 */
public record PaintingEntry(int id, String name, int width, int height, ImageFit fit,
                            String imageFile, String sha256, String recipeId) {
    public static final int TEXTURE_PIXELS_PER_BLOCK = 256;
    /** Keeps even an 8×8 painting at or below 1,048,576 canvas pixels. */
    public static final int MAX_CANVAS_PIXELS = 1_048_576;
    /** A painting with no crafting recipe; it is obtained from the Painting Menu instead. */
    public static final String NO_RECIPE = "";

    public PaintingEntry {
        if (id < 0) {
            throw new IllegalArgumentException("Painting id cannot be negative");
        }
        name = normalizeName(name);
        if (!PaintingSizes.isSupported(width, height)) {
            throw new IllegalArgumentException("Unsupported painting size: " + width + "x" + height);
        }
        Objects.requireNonNull(fit, "fit");
        if (imageFile == null || !imageFile.equals(imageFileFor(id))) {
            throw new IllegalArgumentException("Image file must be the canonical PNG for its painting id");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256 digest");
        }
        recipeId = recipeId == null ? NO_RECIPE : recipeId;
        if (!recipeId.isEmpty() && !RecipeCatalog.contains(recipeId)) {
            throw new IllegalArgumentException("Unknown crafting recipe: " + recipeId);
        }
    }

    /** Canonical, collision-free master PNG name for a painting id. */
    public static String imageFileFor(int id) {
        return "painting_" + id + ".png";
    }

    public static String normalizeName(String name) {
        String cleaned = name == null ? "" : name.strip();
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Painting name cannot be empty");
        }
        if (cleaned.length() > 48) {
            throw new IllegalArgumentException("Painting name cannot exceed 48 characters");
        }
        if (cleaned.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Painting name cannot contain control characters");
        }
        return cleaned;
    }

    public boolean hasRecipe() {
        return !recipeId.isEmpty();
    }

    /** Larger paintings reduce pixels-per-block so a single canvas stays bounded. */
    public int pixelsPerBlock() {
        int scale = (int) Math.floor(Math.sqrt((double) MAX_CANVAS_PIXELS / (width * height)));
        return Math.max(1, Math.min(TEXTURE_PIXELS_PER_BLOCK, scale));
    }

    public int textureWidth() {
        return width * pixelsPerBlock();
    }

    public int textureHeight() {
        return height * pixelsPerBlock();
    }

    public PaintingEntry withName(String newName) {
        return new PaintingEntry(id, newName, width, height, fit, imageFile, sha256, recipeId);
    }

    public PaintingEntry withSize(int newWidth, int newHeight) {
        return new PaintingEntry(id, name, newWidth, newHeight, fit, imageFile, sha256, recipeId);
    }

    public PaintingEntry withFit(ImageFit newFit) {
        return new PaintingEntry(id, name, width, height, newFit, imageFile, sha256, recipeId);
    }

    public PaintingEntry withRecipe(String newRecipeId) {
        return new PaintingEntry(id, name, width, height, fit, imageFile, sha256, newRecipeId);
    }

    public String sizeLabel() {
        return width + " × " + height;
    }

    public String fileNameForDisplay() {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
    }
}
