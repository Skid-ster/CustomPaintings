package com.jay.custompaintings.image;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Validates and copies user-selected images without touching Minecraft or the GPU.
 * Work is sequential and bounded; callers run this off the client thread.
 */
public final class ImageImporter {
    public static final int MAX_BATCH_FILES = 64;
    private static final int MAX_STEM_CODE_POINTS = 96;
    private static final String STAGING_DIRECTORY = ".imports";
    private final Path directory;

    public ImageImporter(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    }

    public enum Status { IMPORTED, DUPLICATE, FAILED }

    public record Result(String sourceName, String fileName, Status status, String detail) {
        public Result {
            Objects.requireNonNull(sourceName, "sourceName");
            Objects.requireNonNull(status, "status");
        }
    }

    public record Report(List<Result> results) {
        public Report {
            results = List.copyOf(results);
        }

        public long importedCount() {
            return results.stream().filter(result -> result.status() == Status.IMPORTED).count();
        }

        public long duplicateCount() {
            return results.stream().filter(result -> result.status() == Status.DUPLICATE).count();
        }

        public long failedCount() {
            return results.stream().filter(result -> result.status() == Status.FAILED).count();
        }
    }

    @FunctionalInterface
    public interface Progress {
        void update(int completed, int total, String fileName);
    }

    public Report importImages(List<Path> sources) throws IOException {
        return importImages(sources, (completed, total, fileName) -> { });
    }

    /**
     * Imports a batch, continuing after individual failures. Existing files are never
     * overwritten: identical names/content are skipped and different content gets a suffix.
     */
    public Report importImages(List<Path> sources, Progress progress) throws IOException {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(progress, "progress");
        if (sources.size() > MAX_BATCH_FILES) {
            throw new IllegalArgumentException("Select at most " + MAX_BATCH_FILES + " images at a time");
        }
        if (sources.isEmpty()) {
            return new Report(List.of());
        }
        requireDirectory(directory);
        Files.createDirectories(directory);
        Path staging = directory.resolve(STAGING_DIRECTORY);
        requireDirectory(staging);
        Files.createDirectories(staging);

        List<Result> results = new ArrayList<>();
        Set<Path> visited = new HashSet<>();
        int completed = 0;
        for (Path source : sources) {
            String sourceName = source.getFileName() == null ? source.toString() : source.getFileName().toString();
            progress.update(completed, sources.size(), sourceName);
            Path normalized = source.toAbsolutePath().normalize();
            try {
                if (!visited.add(normalized)) {
                    results.add(new Result(sourceName, null, Status.DUPLICATE, null));
                } else {
                    results.add(importOne(normalized, sourceName, staging));
                }
            } catch (IOException | RuntimeException exception) {
                String detail = exception.getMessage() == null
                        ? exception.getClass().getSimpleName() : exception.getMessage();
                results.add(new Result(sourceName, null, Status.FAILED, detail));
            }
            progress.update(++completed, sources.size(), sourceName);
        }
        return new Report(results);
    }

    private Result importOne(Path source, String sourceName, Path staging) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
            throw new IOException("Choose a regular image file, not a folder or symbolic link");
        }
        if (!ImageProcessor.isSupported(sourceName)) {
            throw new IOException("Use a PNG, JPG, or JPEG image");
        }
        if (attributes.size() > ImageProcessor.MAX_FILE_BYTES) {
            throw new IOException("Image exceeds the 32 MiB file-size limit");
        }
        if (source.getParent().equals(directory)) {
            return new Result(sourceName, sourceName, Status.DUPLICATE, null);
        }

        String safeName = safeFileName(sourceName);
        String extension = safeName.substring(safeName.lastIndexOf('.'));
        Path temporary = Files.createTempFile(staging, "upload-", extension);
        try {
            copyBounded(source, temporary);
            // Validate the actual copied bytes, not just the extension or a mutable source.
            ImageProcessor.read(temporary).flush();
            String stem = safeName.substring(0, safeName.length() - extension.length());
            for (int suffix = 0; suffix < 10_000; suffix++) {
                String candidate = suffix == 0 ? safeName : stem + "-" + suffix + extension;
                Path target = directory.resolve(candidate);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    BasicFileAttributes targetAttributes = Files.readAttributes(target,
                            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!targetAttributes.isSymbolicLink() && targetAttributes.isRegularFile()
                            && Files.mismatch(temporary, target) == -1L) {
                        return new Result(sourceName, candidate, Status.DUPLICATE, null);
                    }
                    continue;
                }
                try {
                    // No REPLACE_EXISTING: a competing import or external edit cannot be overwritten.
                    Files.move(temporary, target);
                    return new Result(sourceName, candidate, Status.IMPORTED, null);
                } catch (FileAlreadyExistsException ignored) {
                    // Another writer won the name; try the next suffix.
                }
            }
            throw new IOException("Too many existing images with this filename");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void copyBounded(Path source, Path target) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
             OutputStream output = Files.newOutputStream(target)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > ImageProcessor.MAX_FILE_BYTES) {
                    throw new IOException("Image grew beyond the 32 MiB file-size limit while copying");
                }
                output.write(buffer, 0, count);
            }
        }
        if (total == 0) {
            throw new IOException("The selected image is empty");
        }
    }

    private static void requireDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw new IOException("Image storage must be a real directory, not a file or symbolic link");
            }
        }
    }

    /** Keeps names portable across Windows/Linux/macOS and prevents path traversal. */
    public static String safeFileName(String sourceName) {
        String normalized = Normalizer.normalize(sourceName, Normalizer.Form.NFC);
        int dot = normalized.lastIndexOf('.');
        if (dot < 0 || !ImageProcessor.isSupported(normalized)) {
            throw new IllegalArgumentException("Use a PNG, JPG, or JPEG image");
        }
        String extension = normalized.substring(dot).toLowerCase(Locale.ROOT);
        String stem = normalized.substring(0, dot).replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_");
        stem = stem.replaceAll("[ .]+$", "");
        if (stem.isBlank()) {
            stem = "image";
        }
        if (stem.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])")) {
            stem = "_" + stem;
        }
        int length = stem.codePointCount(0, stem.length());
        if (length > MAX_STEM_CODE_POINTS) {
            stem = stem.substring(0, stem.offsetByCodePoints(0, MAX_STEM_CODE_POINTS));
        }
        return stem + extension;
    }
}
