package com.jay.custompaintings.library;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PaintingLibraryTest {
    private static PaintingEntry entry(int slot, String recipe) {
        return new PaintingEntry(slot, "Painting " + slot, 2, 2, "slot_" + slot + ".png", "a".repeat(64), recipe);
    }

    @Test
    void enforcesTenSlotCapAndUniqueSlots() {
        PaintingLibrary library = new PaintingLibrary(1, 42, List.of());
        for (int slot = 0; slot < 10; slot++) {
            library = library.withAdded(entry(slot, RecipeCatalog.RECIPES.get(slot).id()));
        }
        assertEquals(10, library.count());
        assertEquals(0, library.freeSlots());
        assertTrue(library.firstFreeSlot().isEmpty());
        PaintingLibrary full = library;
        assertThrows(IllegalArgumentException.class, () -> full.withAdded(entry(0, "paper_black")));
        assertThrows(IllegalArgumentException.class, () -> new PaintingLibrary(0, 0,
                java.util.stream.IntStream.range(0, 11).mapToObj(i -> entry(i % 10, RecipeCatalog.RECIPES.get(i % 10).id())).toList()));
    }

    @Test
    void reusesFreedSlotsAndRenamesImmutably() {
        PaintingLibrary library = new PaintingLibrary(0, 7, List.of(entry(0, "paper_black"), entry(1, "paper_blue")));
        PaintingLibrary removed = library.without(0);
        assertEquals(0, removed.firstFreeSlot().orElseThrow());
        PaintingLibrary renamed = removed.withRenamed(1, "New Name");
        assertEquals("New Name", renamed.get(1).orElseThrow().name());
        assertEquals("Painting 1", library.get(1).orElseThrow().name());
        assertEquals(removed.revision() + 1, renamed.revision());
    }

    @Test
    void randomRecipeAssignmentIsUniqueAndStable() {
        List<String> first = RecipeCatalog.shuffledIds(123);
        assertEquals(first, RecipeCatalog.shuffledIds(123));
        assertEquals(10, first.size());
        assertEquals(10, new HashSet<>(first).size());
        assertNotEquals(first, RecipeCatalog.shuffledIds(456));
    }

    @Test
    void namesAndSizesAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> PaintingEntry.normalizeName("  "));
        assertThrows(IllegalArgumentException.class, () -> PaintingEntry.normalizeName("a".repeat(49)));
        assertThrows(IllegalArgumentException.class, () -> PaintingEntry.normalizeName("bad\u0000name"));
        assertEquals("Sunset", PaintingEntry.normalizeName(" Sunset "));
        assertTrue(PaintingSizes.isSupported(4, 4));
        assertFalse(PaintingSizes.isSupported(5, 1));
        assertThrows(IllegalArgumentException.class, () -> entry(10, "paper_black"));
        assertThrows(IllegalArgumentException.class, () -> new PaintingEntry(0, "X", 2, 2, "slot_1.png", "a".repeat(64), "paper_black"));
    }

}
