package com.jay.custompaintings.library;

import com.jay.custompaintings.image.ImageFit;

import java.util.Objects;

/** One staged metadata edit. The master image, digest, slot and recipe are never changed by an edit. */
public record PaintingEdit(int slot, String name, int width, int height, ImageFit fit) {
    public PaintingEdit {
        if (slot < 0 || slot >= PaintingEntry.MAX_PAINTINGS) {
            throw new IllegalArgumentException("Invalid painting slot: " + slot);
        }
        name = PaintingEntry.normalizeName(name);
        if (!PaintingSizes.isSupported(width, height)) {
            throw new IllegalArgumentException("Unsupported painting size: " + width + "x" + height);
        }
        Objects.requireNonNull(fit, "fit");
    }

    public static PaintingEdit from(PaintingEntry entry) {
        return new PaintingEdit(entry.slot(), entry.name(), entry.width(), entry.height(), entry.fit());
    }

    public PaintingEntry applyTo(PaintingEntry entry) {
        if (entry.slot() != slot) {
            throw new IllegalArgumentException("Edit slot does not match painting entry");
        }
        return new PaintingEntry(slot, name, width, height, fit, entry.imageFile(), entry.sha256(), entry.recipeId());
    }
}
