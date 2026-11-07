package com.jay.custompaintings.library;

import java.util.Locale;
import java.util.Objects;

/** One occupied custom-painting slot. Slots are stable so placed entities survive renames and relogs. */
public record PaintingEntry(int slot, String name, int width, int height, String imageFile, String sha256, String recipeId) {
    public static final int MAX_PAINTINGS = 10;
    public static final int TEXTURE_PIXELS_PER_BLOCK = 256;

    public PaintingEntry {
        if (slot < 0 || slot >= MAX_PAINTINGS) {
            throw new IllegalArgumentException("Slot must be between 0 and " + (MAX_PAINTINGS - 1));
        }
        name = normalizeName(name);
        if (!PaintingSizes.isSupported(width, height)) {
            throw new IllegalArgumentException("Unsupported painting size: " + width + "x" + height);
        }
        if (imageFile == null || !imageFile.matches("slot_[0-9]\\.png") || !imageFile.equals("slot_" + slot + ".png")) {
            throw new IllegalArgumentException("Image file must be the canonical PNG for its slot");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid SHA-256 digest");
        }
        Objects.requireNonNull(recipeId, "recipeId");
        if (!RecipeCatalog.contains(recipeId)) {
            throw new IllegalArgumentException("Unknown easy recipe: " + recipeId);
        }
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

    public int textureWidth() {
        return width * TEXTURE_PIXELS_PER_BLOCK;
    }

    public int textureHeight() {
        return height * TEXTURE_PIXELS_PER_BLOCK;
    }

    public String variantPath() {
        return "slot_" + slot + "_" + width + "x" + height;
    }

    public String itemPath() {
        return "slot_" + slot;
    }

    public PaintingEntry withName(String newName) {
        return new PaintingEntry(slot, newName, width, height, imageFile, sha256, recipeId);
    }

    public String sizeLabel() {
        return width + " × " + height;
    }

    public String fileNameForDisplay() {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "_");
    }
}
