package com.jay.custompaintings.library;

import com.jay.custompaintings.image.ImageProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PaintingLibraryStoreTest {
    @TempDir
    Path temp;

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        return PaintingLibraryStore.encodePng(ImageProcessor.resize(image, width, height));
    }

    @Test
    void addsPersistsAndReloadsCanonicalPng() throws Exception {
        PaintingLibraryStore store = new PaintingLibraryStore(temp);
        store.load();
        PaintingEntry entry = store.add("Sunset", 2, 2, png(512, 512));
        assertEquals(0, entry.slot());
        assertTrue(Files.exists(store.imagePath(entry)));
        assertTrue(Files.exists(temp.resolve("library.json")));

        PaintingLibraryStore reloaded = new PaintingLibraryStore(temp);
        reloaded.load();
        assertEquals("Sunset", reloaded.library().get(0).orElseThrow().name());
        assertEquals(entry.sha256(), reloaded.library().get(0).orElseThrow().sha256());
    }

    @Test
    void enforcesCapAndRejectsMismatchedDimensions() throws Exception {
        PaintingLibraryStore store = new PaintingLibraryStore(temp);
        store.load();
        for (int i = 0; i < 10; i++) {
            store.add("Painting " + i, 1, 1, png(256, 256));
        }
        assertThrows(java.io.IOException.class, () -> store.add("One too many", 1, 1, png(256, 256)));
        PaintingLibraryStore other = new PaintingLibraryStore(temp.resolve("other"));
        other.load();
        assertThrows(java.io.IOException.class, () -> other.add("Bad", 2, 2, png(256, 256)));
    }

    @Test
    void renameResizeAndRemoveKeepLibraryConsistent() throws Exception {
        PaintingLibraryStore store = new PaintingLibraryStore(temp);
        store.load();
        PaintingEntry entry = store.add("Before", 1, 1, png(256, 256));
        store.rename(0, "After");
        store.resize(0, 2, 1);
        PaintingEntry resized = store.library().get(0).orElseThrow();
        assertEquals("After", resized.name());
        assertEquals(512, resized.textureWidth());
        assertEquals(256, resized.textureHeight());
        assertNotEquals(entry.sha256(), resized.sha256());
        store.remove(0);
        assertTrue(store.library().entries().isEmpty());
        assertFalse(Files.exists(store.imagePath(entry)));
    }
}
