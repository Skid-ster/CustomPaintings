package com.jay.custompaintings.library;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaintingLibraryTest {
    private static PaintingEntry entry(int id, String recipe) {
        return new PaintingEntry(id, "Painting " + id, 2, 2, ImageFit.FIT,
                PaintingEntry.imageFileFor(id), "a".repeat(64), recipe);
    }

    @Test
    void supportsFarMoreThanTheRecipeCatalogue() {
        // The old design stopped at 10 paintings. Now only the finite recipe pool runs out.
        PaintingLibrary library = new PaintingLibrary(1, 42, List.of());
        for (int id = 0; id < 500; id++) {
            library = library.withAdded(entry(id, ""));
        }
        assertEquals(500, library.count());
        assertEquals(500, library.nextId());
        for (int id = 0; id < 500; id++) {
            assertEquals(id, library.get(id).orElseThrow().id());
        }
        assertTrue(library.entries().stream().noneMatch(PaintingEntry::hasRecipe));
    }

    @Test
    void assignsRecipesUntilTheCatalogueIsExhaustedThenStopsAssigning() {
        PaintingLibrary library = new PaintingLibrary(1, 42, List.of());
        for (int id = 0; id < RecipeCatalog.size(); id++) {
            library = library.withAdded(entry(id, library.nextRecipe()));
        }
        assertEquals(RecipeCatalog.size(), library.count());
        assertEquals(RecipeCatalog.size(), library.entries().stream().filter(PaintingEntry::hasRecipe).count());
        assertEquals("", library.nextRecipe());
        // Painting number 193 still uploads; it simply has no crafting recipe.
        library = library.withAdded(entry(RecipeCatalog.size(), library.nextRecipe()));
        assertEquals(RecipeCatalog.size() + 1, library.count());
        assertFalse(library.get(RecipeCatalog.size()).orElseThrow().hasRecipe());
    }

    @Test
    void rejectsDuplicateIdsAndRecipesButNotLargeIds() {
        PaintingLibrary library = new PaintingLibrary(0, 7, List.of(entry(4, "oak_red")));
        assertThrows(IllegalArgumentException.class, () -> library.withAdded(entry(4, "oak_blue")));
        assertThrows(IllegalArgumentException.class, () -> library.withAdded(entry(5, "oak_red")));
        assertThrows(IllegalArgumentException.class, () -> entry(-1, ""));
        // Huge sparse ids are legal: nothing is allocated per id.
        PaintingLibrary sparse = new PaintingLibrary(0, 7, List.of(entry(1_000_000, "")));
        assertEquals(1_000_001, sparse.nextId());
        assertEquals(1_000_000, sparse.get(1_000_000).orElseThrow().id());
    }

    @Test
    void removalFreesTheRecipeAndIdsKeepIncreasing() {
        PaintingLibrary library = new PaintingLibrary(0, 7, List.of(entry(0, "oak_red"), entry(1, "oak_blue")));
        PaintingLibrary removed = library.without(0);
        assertEquals(1, removed.count());
        assertTrue(removed.get(0).isEmpty());
        // nextId never reuses a removed id, so old items in chests cannot silently change art.
        assertEquals(2, removed.nextId());
        assertFalse(removed.nextRecipe().isEmpty());
        PaintingLibrary renamed = removed.withRenamed(1, "New Name");
        assertEquals("New Name", renamed.get(1).orElseThrow().name());
        assertEquals("Painting 1", library.get(1).orElseThrow().name());
        assertEquals(removed.revision() + 1, renamed.revision());
    }

    @Test
    void randomRecipeAssignmentIsUniqueAndStable() {
        List<String> first = RecipeCatalog.shuffledIds(123);
        assertEquals(first, RecipeCatalog.shuffledIds(123));
        assertEquals(RecipeCatalog.size(), first.size());
        assertEquals(RecipeCatalog.size(), new HashSet<>(first).size());
        assertNotEquals(first, RecipeCatalog.shuffledIds(456));
    }

    @Test
    void namesAndSizesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> PaintingEntry.normalizeName("  "));
        assertThrows(IllegalArgumentException.class, () -> PaintingEntry.normalizeName("a".repeat(49)));
        assertThrows(IllegalArgumentException.class, () -> PaintingEntry.normalizeName("bad\u0000name"));
        assertEquals("Sunset", PaintingEntry.normalizeName(" Sunset "));
        assertTrue(PaintingSizes.isSupported(4, 4));
        assertTrue(PaintingSizes.isSupported(5, 1));
        assertTrue(PaintingSizes.isSupported(8, 8));
        assertFalse(PaintingSizes.isSupported(9, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new PaintingEntry(0, "X", 2, 2, ImageFit.FIT, "painting_1.png", "a".repeat(64), ""));
        assertThrows(IllegalArgumentException.class,
                () -> new PaintingEntry(0, "X", 2, 2, ImageFit.FIT, "painting_0.png", "a".repeat(64), "paper_black"));
    }

    @Test
    void editAppliesAllFieldsAtOnceAndBumpsRevisionOnce() {
        PaintingLibrary library = new PaintingLibrary(5, 7, List.of(entry(0, "oak_red")));
        PaintingEntry original = library.get(0).orElseThrow();
        PaintingEdit edit = new PaintingEdit(0, "Renamed", 5, 2, ImageFit.FILL);
        PaintingLibrary updated = library.withReplaced(edit.applyTo(original));
        PaintingEntry entry = updated.get(0).orElseThrow();
        assertEquals("Renamed", entry.name());
        assertEquals(5, entry.width());
        assertEquals(2, entry.height());
        assertEquals(ImageFit.FILL, entry.fit());
        assertEquals(6, updated.revision());
        assertEquals("a".repeat(64), entry.sha256(), "an edit must not change the artwork hash");
    }
}
