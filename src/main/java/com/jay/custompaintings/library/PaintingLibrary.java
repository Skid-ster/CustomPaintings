package com.jay.custompaintings.library;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/** Immutable bounded library snapshot used by both the authoritative server and synced clients. */
public record PaintingLibrary(long revision, long recipeSeed, List<PaintingEntry> entries) {
    public static final PaintingLibrary EMPTY = new PaintingLibrary(0, 0, List.of());

    public PaintingLibrary {
        if (revision < 0) {
            throw new IllegalArgumentException("Revision cannot be negative");
        }
        entries = List.copyOf(entries);
        if (entries.size() > PaintingEntry.MAX_PAINTINGS) {
            throw new IllegalArgumentException("At most " + PaintingEntry.MAX_PAINTINGS + " custom paintings are allowed");
        }
        Set<Integer> slots = new HashSet<>();
        Set<String> recipes = new HashSet<>();
        for (PaintingEntry entry : entries) {
            if (!slots.add(entry.slot())) {
                throw new IllegalArgumentException("Duplicate painting slot: " + entry.slot());
            }
            if (!recipes.add(entry.recipeId())) {
                throw new IllegalArgumentException("Duplicate painting recipe: " + entry.recipeId());
            }
        }
        entries = entries.stream().sorted(Comparator.comparingInt(PaintingEntry::slot)).toList();
    }

    public Optional<PaintingEntry> get(int slot) {
        return entries.stream().filter(entry -> entry.slot() == slot).findFirst();
    }

    public boolean isOccupied(int slot) {
        return get(slot).isPresent();
    }

    public int count() {
        return entries.size();
    }

    public int freeSlots() {
        return PaintingEntry.MAX_PAINTINGS - entries.size();
    }

    public OptionalInt firstFreeSlot() {
        for (int slot = 0; slot < PaintingEntry.MAX_PAINTINGS; slot++) {
            if (!isOccupied(slot)) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    public PaintingLibrary withAdded(PaintingEntry entry) {
        if (isOccupied(entry.slot())) {
            throw new IllegalArgumentException("Painting slot already occupied");
        }
        List<PaintingEntry> updated = new ArrayList<>(entries);
        updated.add(entry);
        return new PaintingLibrary(revision + 1, recipeSeed, updated);
    }

    public PaintingLibrary withRenamed(int slot, String name) {
        PaintingEntry existing = get(slot).orElseThrow(() -> new IllegalArgumentException("Empty painting slot"));
        return replace(existing.withName(name));
    }

    public PaintingLibrary without(int slot) {
        if (!isOccupied(slot)) {
            throw new IllegalArgumentException("Empty painting slot");
        }
        return new PaintingLibrary(revision + 1, recipeSeed, entries.stream().filter(entry -> entry.slot() != slot).toList());
    }

    private PaintingLibrary replace(PaintingEntry replacement) {
        return new PaintingLibrary(revision + 1, recipeSeed,
                entries.stream().map(entry -> entry.slot() == replacement.slot() ? replacement : entry).toList());
    }

    public String recipeFor(int slot) {
        return RecipeCatalog.shuffledIds(recipeSeed).get(slot);
    }
}
