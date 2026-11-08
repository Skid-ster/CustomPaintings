package com.jay.custompaintings.library;

import com.jay.custompaintings.image.ImageFit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaintingSizesTest {
    @Test
    void supportsEveryIndependentSizeFromOneToEight() {
        assertEquals(64, PaintingSizes.PRESETS.size());
        for (int width = 1; width <= 8; width++) {
            for (int height = 1; height <= 8; height++) {
                assertTrue(PaintingSizes.isSupported(width, height));
                assertEquals(width, PaintingSizes.of(width, height).width());
                assertEquals(height, PaintingSizes.of(width, height).height());
            }
        }
        assertFalse(PaintingSizes.isSupported(0, 1));
        assertFalse(PaintingSizes.isSupported(1, 0));
        assertFalse(PaintingSizes.isSupported(9, 1));
        assertFalse(PaintingSizes.isSupported(1, 9));
        assertThrows(IllegalArgumentException.class, () -> PaintingSizes.of(8, 9));
    }

    @Test
    void autoSizeMatchesImageProportions() {
        assertEquals(PaintingSizes.of(2, 2), PaintingSizes.auto(512, 512));
        assertEquals(PaintingSizes.of(2, 1), PaintingSizes.auto(1024, 512));
        assertEquals(PaintingSizes.of(1, 2), PaintingSizes.auto(512, 1024));
        assertEquals(PaintingSizes.of(4, 3), PaintingSizes.auto(800, 600));
        assertEquals(PaintingSizes.of(3, 4), PaintingSizes.auto(600, 800));
        assertEquals(PaintingSizes.of(7, 4), PaintingSizes.auto(1920, 1080));
        assertEquals(PaintingSizes.of(4, 7), PaintingSizes.auto(1080, 1920));
        assertThrows(IllegalArgumentException.class, () -> PaintingSizes.auto(0, 10));
    }

    @Test
    void largeCanvasesStayWithinThePixelBudget() {
        PaintingEntry entry = new PaintingEntry(0, "Large", 8, 8, ImageFit.FIT, "painting_0.png", "a".repeat(64), "oak_red");
        assertEquals(128, entry.pixelsPerBlock());
        assertEquals(1024, entry.textureWidth());
        assertEquals(1024, entry.textureHeight());
        assertTrue((long) entry.textureWidth() * entry.textureHeight() <= PaintingEntry.MAX_CANVAS_PIXELS);
        PaintingEntry wide = new PaintingEntry(1, "Wide", 8, 1, ImageFit.FIT, "painting_1.png", "b".repeat(64), "oak_blue");
        assertEquals(2048, wide.textureWidth());
        assertEquals(256, wide.textureHeight());
    }
}
