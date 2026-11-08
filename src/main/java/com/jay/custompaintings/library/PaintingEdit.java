package com.jay.custompaintings.library;

import com.jay.custompaintings.image.ImageFit;

import java.util.Objects;

/** One staged metadata edit. The master image, digest, id and recipe are never changed by an edit. */
public record PaintingEdit(int id, String name, int width, int height, ImageFit fit) {
    public PaintingEdit {
        if (id < 0) {
            throw new IllegalArgumentException("Invalid painting id: " + id);
        }
        name = PaintingEntry.normalizeName(name);
        if (!PaintingSizes.isSupported(width, height)) {
            throw new IllegalArgumentException("Unsupported painting size: " + width + "x" + height);
        }
        Objects.requireNonNull(fit, "fit");
    }

    public static PaintingEdit from(PaintingEntry entry) {
        return new PaintingEdit(entry.id(), entry.name(), entry.width(), entry.height(), entry.fit());
    }

    public PaintingEntry applyTo(PaintingEntry entry) {
        if (entry.id() != id) {
            throw new IllegalArgumentException("Edit id does not match painting entry");
        }
        return new PaintingEntry(id, name, width, height, fit, entry.imageFile(), entry.sha256(), entry.recipeId());
    }
}
