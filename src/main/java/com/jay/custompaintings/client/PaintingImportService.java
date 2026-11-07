package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.image.ImageImporter;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns one import/reload at a time. Imports continue safely if the config screen closes;
 * reopening it shows the same progress/result. State is atomic; completion callbacks use the client thread.
 */
public final class PaintingImportService {
    private final Path directory;
    private final AtomicReference<State> state = new AtomicReference<>(State.idle());
    private final Object lock = new Object();

    public PaintingImportService(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public enum Phase { IDLE, IMPORTING, REMOVING, RELOADING, FINISHED, FAILED }

    public record State(Phase phase, int completed, int total, String currentFile,
                        ImageImporter.Report report, String error) {
        private static State idle() {
            return new State(Phase.IDLE, 0, 0, "", null, null);
        }

        public boolean busy() {
            return phase == Phase.IMPORTING || phase == Phase.REMOVING || phase == Phase.RELOADING;
        }
    }

    public State state() {
        return state.get();
    }

    /** Called on the client thread by the picker or drag-and-drop handler. */
    public boolean importImages(List<Path> files) {
        List<Path> copy = List.copyOf(files);
        if (copy.isEmpty() || copy.size() > ImageImporter.MAX_BATCH_FILES) {
            return false;
        }
        synchronized (lock) {
            if (state().busy()) {
                return false;
            }
            state.set(new State(Phase.IMPORTING, 0, copy.size(), "", null, null));
        }
        Minecraft minecraft = Minecraft.getInstance();
        try {
            CompletableFuture.supplyAsync(() -> {
                try {
                    return new ImageImporter(directory).importImages(copy, (completed, total, fileName) ->
                            state.set(new State(Phase.IMPORTING, completed, total, fileName, null, null)));
                } catch (Exception exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            }, Util.ioPool()).whenCompleteAsync((report, error) -> {
                if (error != null) {
                    fail(report, error);
                } else if (report.importedCount() > 0) {
                    reload(report);
                } else {
                    state.set(new State(Phase.FINISHED, report.results().size(), report.results().size(), "", report, null));
                }
            }, minecraft);
        } catch (RuntimeException exception) {
            fail(null, exception);
        }
        return true;
    }

    /** Removes only a named, regular, non-symlink image inside the managed directory. */
    public boolean removeImage(String fileName) {
        if (!com.jay.custompaintings.image.ImageProcessor.isSupported(fileName)) {
            return false;
        }
        Path library = directory.toAbsolutePath().normalize();
        Path file = library.resolve(fileName).normalize();
        if (!file.getParent().equals(library) || !file.getFileName().toString().equals(fileName)) {
            return false;
        }
        synchronized (lock) {
            if (state().busy()) {
                return false;
            }
            state.set(new State(Phase.REMOVING, 0, 1, fileName, null, null));
        }
        try {
            CompletableFuture.supplyAsync(() -> {
                try {
                    if (java.nio.file.Files.isSymbolicLink(file)
                            || !java.nio.file.Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        throw new java.io.IOException("The image is no longer a regular file");
                    }
                    return java.nio.file.Files.deleteIfExists(file);
                } catch (java.io.IOException exception) {
                    throw new java.util.concurrent.CompletionException(exception);
                }
            }, Util.ioPool()).whenCompleteAsync((deleted, error) -> {
                if (error != null) {
                    fail(null, error);
                } else if (Boolean.TRUE.equals(deleted)) {
                    reload(null);
                } else {
                    state.set(new State(Phase.FINISHED, 1, 1, fileName, null, null));
                }
            }, Minecraft.getInstance());
        } catch (RuntimeException exception) {
            fail(null, exception);
        }
        return true;
    }

    /** Manual resource reload shares the busy guard with imports. */
    public boolean reloadPaintings() {
        synchronized (lock) {
            if (state().busy()) {
                return false;
            }
            state.set(new State(Phase.RELOADING, 0, 0, "", null, null));
        }
        reload(null);
        return true;
    }

    private void reload(ImageImporter.Report report) {
        state.set(new State(Phase.RELOADING, report == null ? 0 : report.results().size(),
                report == null ? 0 : report.results().size(), "", report, null));
        try {
            Minecraft.getInstance().reloadResourcePacks().whenCompleteAsync((ignored, error) -> {
                if (error != null) {
                    fail(report, error);
                } else {
                    state.set(new State(Phase.FINISHED, report == null ? 0 : report.results().size(),
                            report == null ? 0 : report.results().size(), "", report, null));
                }
            }, Minecraft.getInstance());
        } catch (RuntimeException exception) {
            fail(report, exception);
        }
    }

    private void fail(ImageImporter.Report report, Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        CustomPaintingsMod.LOGGER.error("Custom Paintings import/reload failed", cause);
        state.set(new State(Phase.FAILED, report == null ? 0 : report.results().size(),
                report == null ? 0 : report.results().size(), "", report, message));
    }
}
