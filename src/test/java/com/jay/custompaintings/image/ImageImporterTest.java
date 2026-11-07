package com.jay.custompaintings.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageImporterTest {
    @TempDir
    Path temporary;

    @Test
    void importsPngAndJpegWithoutChangingBytes() throws IOException {
        Path source = directory("source");
        Path target = directory("target");
        Path png = image(source.resolve("holiday.png"), "png", 0x80FF1020);
        Path jpg = image(source.resolve("photo.JPG"), "jpg", 0xFF1020FF);
        byte[] pngBytes = Files.readAllBytes(png);
        byte[] jpgBytes = Files.readAllBytes(jpg);

        ImageImporter.Report report = new ImageImporter(target).importImages(List.of(png, jpg));

        assertEquals(2, report.importedCount());
        assertEquals(0, report.failedCount());
        assertArrayEquals(pngBytes, Files.readAllBytes(target.resolve("holiday.png")));
        assertArrayEquals(jpgBytes, Files.readAllBytes(target.resolve("photo.jpg")));
        assertTrue(ImageProcessor.isSupported(report.results().getLast().fileName()));
    }

    @Test
    void identicalImageIsSkippedButDifferentImageGetsSafeSuffix() throws IOException {
        Path source = directory("source");
        Path target = directory("target");
        Path first = image(source.resolve("art.png"), "png", 0xFFFF0000);
        ImageImporter importer = new ImageImporter(target);
        importer.importImages(List.of(first));
        byte[] original = Files.readAllBytes(target.resolve("art.png"));

        ImageImporter.Report duplicate = importer.importImages(List.of(first));
        assertEquals(1, duplicate.duplicateCount());
        assertEquals(0, duplicate.importedCount());

        image(first, "png", 0xFF00FF00);
        ImageImporter.Report changed = importer.importImages(List.of(first));
        assertEquals(1, changed.importedCount());
        assertEquals("art-1.png", changed.results().getFirst().fileName());
        assertArrayEquals(original, Files.readAllBytes(target.resolve("art.png")));
        assertEquals(0xFF00FF00, ImageIO.read(target.resolve("art-1.png").toFile()).getRGB(0, 0));
    }

    @Test
    void corruptUnsupportedAndEmptyFilesNeverReachLibrary() throws IOException {
        Path source = directory("source");
        Path target = directory("target");
        Path corrupt = Files.writeString(source.resolve("corrupt.png"), "not a png");
        Path unsupported = Files.writeString(source.resolve("image.gif"), "GIF89a");
        Path empty = Files.createFile(source.resolve("empty.jpg"));

        ImageImporter.Report report = new ImageImporter(target).importImages(List.of(corrupt, unsupported, empty));

        assertEquals(3, report.failedCount());
        assertEquals(0, report.importedCount());
        try (var files = Files.list(target)) {
            assertTrue(files.noneMatch(path -> ImageProcessor.isSupported(path.getFileName().toString())));
        }
        assertFalse(Files.exists(target.resolve("corrupt.png")));
    }

    @Test
    void foldersAndSymlinksAreRejected() throws IOException {
        Path source = directory("source");
        Path target = directory("target");
        Path png = image(source.resolve("real.png"), "png", 0xFF123456);
        Path link = source.resolve("link.png");
        try {
            Files.createSymbolicLink(link, png);
        } catch (UnsupportedOperationException | IOException exception) {
            // Some Windows accounts cannot create symlinks; the folder case still runs.
            link = null;
        }
        List<Path> selection = new ArrayList<>();
        selection.add(source);
        if (link != null) {
            selection.add(link);
        }
        ImageImporter.Report report = new ImageImporter(target).importImages(selection);
        assertEquals(selection.size(), report.failedCount());
        assertFalse(Files.exists(target.resolve("link.png")));
    }

    @Test
    void duplicateSelectionAndLibraryFileAreNotImportedTwice() throws IOException {
        Path source = directory("source");
        Path target = directory("target");
        Path png = image(source.resolve("one.png"), "png", 0xFF112233);
        ImageImporter importer = new ImageImporter(target);
        ImageImporter.Report first = importer.importImages(List.of(png, png));
        assertEquals(1, first.importedCount());
        assertEquals(1, first.duplicateCount());
        ImageImporter.Report second = importer.importImages(List.of(target.resolve("one.png")));
        assertEquals(1, second.duplicateCount());
        assertEquals(0, second.importedCount());
    }

    @Test
    void batchLimitIsEnforcedBeforeAnyWrites() throws IOException {
        Path target = directory("target");
        List<Path> paths = IntStream.range(0, ImageImporter.MAX_BATCH_FILES + 1)
                .mapToObj(i -> target.resolve(i + ".png")).toList();
        assertThrows(IllegalArgumentException.class, () -> new ImageImporter(target).importImages(paths));
        assertFalse(Files.exists(target.resolve(".imports")));
    }

    @Test
    void progressStartsAtZeroAndEndsAtTotal() throws IOException {
        Path source = directory("source");
        Path png = image(source.resolve("progress.png"), "png", 0xFFABCDEF);
        List<String> updates = new ArrayList<>();
        new ImageImporter(directory("target")).importImages(List.of(png, source.resolve("missing.png")),
                (completed, total, fileName) -> updates.add(completed + "/" + total));
        assertEquals(List.of("0/2", "1/2", "1/2", "2/2"), updates);
    }

    @Test
    void filenamesArePortableAndCannotEscapeImageDirectory() {
        assertEquals("kebab.png", ImageImporter.safeFileName("kebab.png"));
        assertEquals("my_image_.png", ImageImporter.safeFileName("my:image?.png"));
        assertEquals("_CON.png", ImageImporter.safeFileName("CON.png"));
        assertEquals("image.jpg", ImageImporter.safeFileName("... .jpg"));
        assertEquals("a_b.png", ImageImporter.safeFileName("a/b.png"));
        assertEquals("a_b.png", ImageImporter.safeFileName("a\\b.png"));
        String longName = ImageImporter.safeFileName("😀".repeat(130) + ".PNG");
        assertTrue(longName.codePointCount(0, longName.length()) <= 100);
        assertTrue(longName.endsWith(".png"));
        assertThrows(IllegalArgumentException.class, () -> ImageImporter.safeFileName("notes.txt"));
    }

    @Test
    void stagingFilesAreCleanedAfterSuccessAndFailure() throws IOException {
        Path source = directory("source");
        Path target = directory("target");
        Path good = image(source.resolve("good.png"), "png", 0xFF102030);
        Path bad = Files.writeString(source.resolve("bad.png"), "broken");
        new ImageImporter(target).importImages(List.of(good, bad));
        try (var files = Files.list(target.resolve(".imports"))) {
            assertEquals(0, files.count());
        }
    }

    private Path directory(String name) throws IOException {
        return Files.createDirectories(temporary.resolve(name));
    }

    private static Path image(Path path, String format, int color) throws IOException {
        int type = format.equals("jpg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        BufferedImage image = new BufferedImage(8, 8, type);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                image.setRGB(x, y, color);
            }
        }
        assertTrue(ImageIO.write(image, format, path.toFile()));
        return path;
    }
}
