package com.jay.custompaintings.library;

import com.jay.custompaintings.image.ImageFit;
import com.jay.custompaintings.image.ImageProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        assertEquals(0, entry.id());
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
    void acceptsMoreThanTenPaintingsAndRejectsInvalidMasters() throws Exception {
        PaintingLibraryStore store = new PaintingLibraryStore(temp);
        store.load();
        for (int i = 0; i < 25; i++) {
            store.add("Painting " + i, 1, 1, png(8, 4));
        }
        assertEquals(25, store.library().count());
        PaintingLibraryStore other = new PaintingLibraryStore(temp.resolve("other"));
        other.load();
        assertThrows(java.io.IOException.class, () -> other.add("Bad", 9, 1, png(8, 4)));
        assertThrows(java.io.IOException.class, () -> other.add("Corrupt", 1, 1, new byte[]{1, 2, 3}));
    }

    @Test
    void applyBatchSavesOncePreservesMastersAndIsAtomic() throws Exception {
        PaintingLibraryStore store = new PaintingLibraryStore(temp);
        store.load();
        PaintingEntry first = store.add("First", 2, 2, ImageFit.FIT, png(64, 32));
        PaintingEntry second = store.add("Second", 1, 1, ImageFit.FIT, png(32, 64));
        long revision = store.library().revision();

        store.applyBatch(revision, List.of(
                new PaintingEdit(0, "First Batch", 8, 8, ImageFit.FILL),
                new PaintingEdit(1, "Second Batch", 4, 2, ImageFit.STRETCH)), List.of());

        assertEquals(revision + 1, store.library().revision());
        PaintingEntry updatedFirst = store.library().get(0).orElseThrow();
        PaintingEntry updatedSecond = store.library().get(1).orElseThrow();
        assertEquals("First Batch", updatedFirst.name());
        assertEquals(8, updatedFirst.width());
        assertEquals(ImageFit.FILL, updatedFirst.fit());
        assertEquals(first.sha256(), updatedFirst.sha256());
        assertEquals("Second Batch", updatedSecond.name());
        assertEquals(4, updatedSecond.width());
        assertEquals(2, updatedSecond.height());
        assertEquals(second.sha256(), updatedSecond.sha256());
        assertArrayEquals(Files.readAllBytes(store.imagePath(first)), Files.readAllBytes(store.imagePath(updatedFirst)));

        long afterBatch = store.library().revision();
        assertThrows(java.io.IOException.class, () -> store.applyBatch(afterBatch, List.of(
                new PaintingEdit(0, "Valid", 1, 1, ImageFit.FIT),
                new PaintingEdit(2, "Missing", 1, 1, ImageFit.FIT)), List.of()));
        assertEquals(afterBatch, store.library().revision());
        assertEquals("First Batch", store.library().get(0).orElseThrow().name());
        assertThrows(java.io.IOException.class, () -> store.applyBatch(afterBatch - 1,
                List.of(new PaintingEdit(0, "Stale", 1, 1, ImageFit.FIT)), List.of()));

        store.applyBatch(afterBatch, List.of(), List.of(1));
        assertEquals(afterBatch + 1, store.library().revision());
        assertTrue(store.library().get(1).isEmpty());
        assertFalse(Files.exists(store.imagePath(second)));
        assertTrue(Files.exists(store.imagePath(updatedFirst)));
    }

    @Test
    void addBatchUploadsSeveralMastersInOneRevision() throws Exception {
        PaintingLibraryStore store = new PaintingLibraryStore(temp);
        store.load();
        List<PaintingEntry> added = store.addBatch(List.of(
                new PaintingLibraryStore.Upload("One", 2, 1, ImageFit.FIT, png(64, 32)),
                new PaintingLibraryStore.Upload("Two", 1, 2, ImageFit.FILL, png(32, 64))));
        assertEquals(2, added.size());
        assertEquals(1, store.library().revision());
        assertEquals(0, added.get(0).id());
        assertEquals(1, added.get(1).id());
        assertEquals(ImageFit.FILL, store.library().get(1).orElseThrow().fit());
        assertThrows(java.io.IOException.class, () -> store.addBatch(List.of(
                new PaintingLibraryStore.Upload("Bad", 1, 1, ImageFit.FIT, new byte[]{1, 2, 3}))));
        assertEquals(2, store.library().count());
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
