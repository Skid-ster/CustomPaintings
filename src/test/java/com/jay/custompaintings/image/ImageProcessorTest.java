package com.jay.custompaintings.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageProcessorTest {
    @TempDir
    Path directory;

    @Test
    void pngIsResizedWithoutLosingAlpha() throws IOException {
        BufferedImage source = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0x80FF0000);
        Path file = directory.resolve("test.png");
        ImageIO.write(source, "png", file.toFile());
        ImageProcessor.Pixels pixels = ImageProcessor.load(file, 16, 16);
        assertEquals(16, pixels.width());
        assertEquals(16, pixels.height());
        assertEquals(256, pixels.argb().length);
        assertTrue((pixels.argb()[0] >>> 24) < 255);
    }

    @Test
    void jpgIsDecodedAndResizedToNonSquareCanvas() throws IOException {
        BufferedImage source = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 20, 10);
        graphics.dispose();
        Path file = directory.resolve("test.JPG");
        assertTrue(ImageIO.write(source, "jpg", file.toFile()));
        ImageProcessor.Pixels pixels = ImageProcessor.load(file, 48, 32);
        assertEquals(48 * 32, pixels.argb().length);
        int center = pixels.argb()[16 * 48 + 24];
        assertEquals(255, center >>> 24);
        assertTrue((center & 255) > (center >> 16 & 255));
    }

    @Test
    void corruptAndUnsupportedFilesFailCleanly() throws IOException {
        Path corrupt = directory.resolve("corrupt.png");
        Files.writeString(corrupt, "not a png");
        assertThrows(IOException.class, () -> ImageProcessor.load(corrupt, 16, 16));
        Path unsupported = directory.resolve("image.gif");
        Files.writeString(unsupported, "GIF89a");
        assertFalse(ImageProcessor.isSupported(unsupported.getFileName().toString()));
        assertThrows(IOException.class, () -> ImageProcessor.read(unsupported));
    }

    @Test
    void imageContentsMustMatchTheExtension() throws IOException {
        BufferedImage source = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        Path mislabeled = directory.resolve("actually-jpeg.png");
        assertTrue(ImageIO.write(source, "jpg", mislabeled.toFile()));
        IOException error = assertThrows(IOException.class, () -> ImageProcessor.read(mislabeled));
        assertTrue(error.getMessage().contains("do not match"));
    }

    @Test
    void invalidOutputDimensionsAreRejected() {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        assertThrows(IllegalArgumentException.class, () -> ImageProcessor.resize(source, 0, 16));
        assertThrows(IllegalArgumentException.class, () -> ImageProcessor.resize(source, 1025, 1024));
    }

    @Test
    void fitPreservesProportionsAndTransparentPadding() {
        BufferedImage source = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        var graphics = source.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 8, 4);
        graphics.dispose();
        ImageProcessor.Pixels pixels = ImageProcessor.compose(source, 16, 16, ImageFit.FIT);
        assertEquals(0, pixels.argb()[0] >>> 24);
        int center = pixels.argb()[8 * 16 + 8];
        assertEquals(255, center >>> 24);
        assertTrue((center >> 16 & 255) > 200);
    }

    @Test
    void fillCropsWithoutDistortionAndStretchFillsExactly() {
        BufferedImage source = new BufferedImage(8, 4, BufferedImage.TYPE_INT_ARGB);
        var graphics = source.createGraphics();
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 0, 8, 4);
        graphics.dispose();
        ImageProcessor.Pixels fill = ImageProcessor.compose(source, 16, 16, ImageFit.FILL);
        assertEquals(255, fill.argb()[0] >>> 24);
        ImageProcessor.Pixels stretch = ImageProcessor.compose(source, 16, 16, ImageFit.STRETCH);
        assertEquals(255, stretch.argb()[0] >>> 24);
        assertEquals(255, stretch.argb()[15 * 16 + 15] >>> 24);
    }

    @Test
    void prepareMasterDownscalesLargeImagesButKeepsSmallOnes() {
        BufferedImage large = new BufferedImage(2048, 1024, BufferedImage.TYPE_INT_ARGB);
        ImageProcessor.Pixels master = ImageProcessor.prepareMaster(large);
        assertTrue((long) master.width() * master.height() <= ImageProcessor.MAX_OUTPUT_PIXELS);
        assertEquals(2, master.width() / master.height());
        BufferedImage small = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
        ImageProcessor.Pixels unchanged = ImageProcessor.prepareMaster(small);
        assertEquals(32, unchanged.width());
        assertEquals(16, unchanged.height());
    }

    @Test
    void supportedExtensionsAreCaseInsensitive() {
        assertTrue(ImageProcessor.isSupported("PHOTO.PNG"));
        assertTrue(ImageProcessor.isSupported("photo.jpg"));
        assertTrue(ImageProcessor.isSupported("photo.JPEG"));
        assertFalse(ImageProcessor.isSupported("photo.webp"));
        assertFalse(ImageProcessor.isSupported("png"));
    }
}
