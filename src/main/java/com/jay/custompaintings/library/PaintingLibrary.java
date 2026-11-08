package com.jay.custompaintings.library;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable, <b>unbounded</b> library snapshot shared by the authoritative server and synced
 * clients. Ids are stable and never reused, so placed entities and held items keep pointing
 * at the same artwork even after the original entry is removed.
 */
public record PaintingLibrary(long revision, long recipeSeed, List<PaintingEntry> entries, int nextId) {
    public static final PaintingLibrary EMPTY = new PaintingLibrary(0, 0, List.of());

    /** Creates a snapshot with its high-water mark inferred from the live entries. */
    public PaintingLibrary(long revision, long recipeSeed, List<PaintingEntry> entries) {
        this(revision, recipeSeed, entries, minimumNextId(entries));
    }

    public PaintingLibrary {
        if (revision < 0) {
            throw new IllegalArgumentException("Revision cannot be negative");
        }
        entries = List.copyOf(entries);
        int minimumNextId = minimumNextId(entries);
        if (nextId < minimumNextId) {
            throw new IllegalArgumentException("Next painting id cannot be below the library high-water mark");
        }
        Set<Integer> ids = new HashSet<>();
        Set<String> recipes = new HashSet<>();
        for (PaintingEntry entry : entries) {
            if (!ids.add(entry.id())) {
                throw new IllegalArgumentException("Duplicate painting id: " + entry.id());
            }
            if (entry.hasRecipe() && !recipes.add(entry.recipeId())) {
                throw new IllegalArgumentException("Duplicate painting recipe: " + entry.recipeId());
            }
        }
        entries = entries.stream().sorted(Comparator.comparingInt(PaintingEntry::id)).toList();
    }

    private static int minimumNextId(List<PaintingEntry> entries) {
        int maximum = -1;
        for (PaintingEntry entry : entries) {
            if (entry.id() == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Painting id space is exhausted");
            }
            maximum = Math.max(maximum, entry.id());
        }
        return maximum + 1;
    }

    public Optional<PaintingEntry> get(int id) {
        // Entries are sorted by id, so binary search keeps lookups cheap in large libraries.
        int low = 0;
        int high = entries.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            PaintingEntry entry = entries.get(mid);
            if (entry.id() == id) {
                return Optional.of(entry);
            }
            if (entry.id() < id) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return Optional.empty();
    }

    public boolean isOccupied(int id) {
        return get(id).isPresent();
    }

    public int count() {
        return entries.size();
    }

    /** Next unused id, including ids reserved by entries that have since been removed. */
    public int nextId() {
        return nextId;
    }

    /**
     * Claims a free wood/dye recipe for a new painting, or {@link PaintingEntry#NO_RECIPE}
     * when every combination in the finite catalogue is already used. Such paintings are
     * obtained from the Painting Menu, which works in Survival and Creative alike.
     */
    public String nextRecipe() {
        Set<String> used = new HashSet<>();
        for (PaintingEntry entry : entries) {
            if (entry.hasRecipe()) {
                used.add(entry.recipeId());
            }
        }
        for (String id : RecipeCatalog.shuffledIds(recipeSeed)) {
            if (!used.contains(id)) {
                return id;
            }
        }
        return PaintingEntry.NO_RECIPE;
    }

    public PaintingLibrary withAdded(PaintingEntry entry) {
        if (isOccupied(entry.id())) {
            throw new IllegalArgumentException("Painting id already occupied");
        }
        if (entry.id() < nextId) {
            throw new IllegalArgumentException("Painting ids cannot be reused");
        }
        if (entry.id() == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Painting id space is exhausted");
        }
        List<PaintingEntry> updated = new ArrayList<>(entries);
        updated.add(entry);
        return new PaintingLibrary(revision + 1, recipeSeed, updated,
                Math.max(nextId, entry.id() + 1));
    }

    public PaintingLibrary withRenamed(int id, String name) {
        PaintingEntry existing = get(id).orElseThrow(() -> new IllegalArgumentException("Unknown painting id"));
        return replace(existing.withName(name));
    }

    public PaintingLibrary withReplaced(PaintingEntry replacement) {
        if (!isOccupied(replacement.id())) {
            throw new IllegalArgumentException("Unknown painting id");
        }
        return replace(replacement);
    }

    public PaintingLibrary without(int id) {
        if (!isOccupied(id)) {
            throw new IllegalArgumentException("Unknown painting id");
        }
        return new PaintingLibrary(revision + 1, recipeSeed,
                entries.stream().filter(entry -> entry.id() != id).toList(), nextId);
    }

    private PaintingLibrary replace(PaintingEntry replacement) {
        return new PaintingLibrary(revision + 1, recipeSeed,
                entries.stream().map(entry -> entry.id() == replacement.id() ? replacement : entry).toList(), nextId);
    }
}
