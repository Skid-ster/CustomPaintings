package com.jay.custompaintings.library;

import com.jay.custompaintings.image.ImageFit;
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
        BufferedImage image = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        return PaintingLibraryStore.encodePng(ImageProcessor.prepareMaster(image));
    }

    @Test
    void addsPersistsAndReloadsCanonicalMasterPng() throws Exception {
        PaintingLibraryStore store = new PaintingLibraryStore(temp);
        store.load();
        PaintingEntry entry = store.add("Sunset", 2, 1, ImageFit.FILL, png(64, 32));
        assertEquals(0, entry.slot());
        assertEquals(ImageFit.FILL, entry.fit());
        assertTrue(Files.exists(store.imagePath(entry)));
        assertTrue(Files.exists(temp.resolve("library.json")));

        PaintingLibraryStore reloaded = new PaintingLibraryStore(temp);
        reloaded.load();
        PaintingEntry loaded = reloaded.library().get(0).orElseThrow();
        assertEquals("Sunset", loaded.name());
        assertEquals(ImageFit.FILL, loaded.fit());
        assertEquals(entry.sha256(), loaded.sha256());
    }

    @Test
    void enforcesCapAndRejectsInvalidMasters() throws Exception {
        PaintingLibraryStore store = new PaintingLibraryStore(temp);
        store.load();
        for (int i = 0; i < 10; i++) {
            store.add("Painting " + i, 1, 1, png(8, 4));
        }
        assertThrows(java.io.IOException.class, () -> store.add("One too many", 1, 1, png(8, 4)));
        PaintingLibraryStore other = new PaintingLibraryStore(temp.resolve("other"));
        other.load();
        assertThrows(java.io.IOException.class, () -> other.add("Bad", 9, 1, png(8, 4)));
        assertThrows(java.io.IOException.class, () -> other.add("Corrupt", 1, 1, new byte[]{1, 2, 3}));
    }

    @Test
    void resizeAndFitEditsPreserveTheMasterNoLossilyReencoded() throws Exception {
        PaintingLibraryStore store = new PaintingLibraryStore(temp);
        store.load();
        PaintingEntry entry = store.add("Before", 1, 1, png(64, 32));
        store.rename(0, "After");
        store.resize(0, 2, 1);
        PaintingEntry resized = store.library().get(0).orElseThrow();
        assertEquals("After", resized.name());
        assertEquals(512, resized.textureWidth());
        assertEquals(256, resized.textureHeight());
        assertEquals(entry.sha256(), resized.sha256());
        assertEquals(entry.fit(), resized.fit());

        store.setFit(0, ImageFit.FILL);
        store.adjust(0, "Adjusted", 8, 8, ImageFit.STRETCH);
        PaintingEntry adjusted = store.library().get(0).orElseThrow();
        assertEquals("Adjusted", adjusted.name());
        assertEquals(8, adjusted.width());
        assertEquals(8, adjusted.height());
        assertEquals(ImageFit.STRETCH, adjusted.fit());
        assertEquals(entry.sha256(), adjusted.sha256());

        PaintingLibraryStore reloaded = new PaintingLibraryStore(temp);
        reloaded.load();
        assertEquals(adjusted, reloaded.library().get(0).orElseThrow());
        store.remove(0);
        assertTrue(store.library().entries().isEmpty());
        assertFalse(Files.exists(store.imagePath(entry)));
    }
}
