package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.image.ImageImporter;
import com.jay.custompaintings.image.ImageProcessor;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibraryStore;
import com.jay.custompaintings.library.PaintingSizes;
import com.jay.custompaintings.network.PaintingPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One bounded client operation at a time; the server remains authoritative and broadcasts syncs. */
public final class PaintingImportService {
    private final ClientPaintingLibrary library;
    private final AtomicReference<State> state = new AtomicReference<>(State.idle());
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<Long, CompletableFuture<PaintingPayloads.Result>> pending = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public PaintingImportService(ClientPaintingLibrary library) {
        this.library = library;
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

    public void complete(PaintingPayloads.Result result) {
        CompletableFuture<PaintingPayloads.Result> future = pending.remove(result.requestId());
        if (future != null) {
            future.complete(result);
        }
    }

    public boolean importImages(List<Path> files) {
        return importImages(files, PaintingSizes.DEFAULT);
    }

    public boolean importImages(List<Path> files, PaintingSizes.Size size) {
        List<Path> copy = List.copyOf(files);
        if (copy.isEmpty() || copy.size() > ImageImporter.MAX_BATCH_FILES || !library.canEdit()) {
            return false;
        }
        synchronized (lock) {
            if (state().busy()) {
                return false;
            }
            if (copy.size() > library.library().freeSlots()) {
                state.set(new State(Phase.FAILED, 0, copy.size(), "", null,
                        "Only " + library.library().freeSlots() + " free painting slot(s) remain"));
                return true;
            }
            state.set(new State(Phase.IMPORTING, 0, copy.size(), "", null, null));
        }
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> uploadBatch(copy, size), Util.ioPool())
                .whenCompleteAsync((report, error) -> finishBatch(report, error), minecraft);
        return true;
    }

    private ImageImporter.Report uploadBatch(List<Path> files, PaintingSizes.Size size) {
        List<ImageImporter.Result> results = new ArrayList<>();
        int completed = 0;
        for (Path file : files) {
            String sourceName = file.getFileName().toString();
            state.set(new State(Phase.IMPORTING, completed, files.size(), sourceName, null, null));
            try {
                BufferedImage image = ImageProcessor.read(file);
                byte[] png = PaintingLibraryStore.encodePng(ImageProcessor.resize(image,
                        size.width() * PaintingEntry.TEXTURE_PIXELS_PER_BLOCK,
                        size.height() * PaintingEntry.TEXTURE_PIXELS_PER_BLOCK));
                String name = defaultName(sourceName);
                PaintingPayloads.Result result = request(new PaintingPayloads.Upload(
                        requestIds.incrementAndGet(), name, size.width(), size.height(), png));
                if (result.success()) {
                    results.add(new ImageImporter.Result(sourceName, name, ImageImporter.Status.IMPORTED, result.message()));
                } else {
                    results.add(new ImageImporter.Result(sourceName, null, ImageImporter.Status.FAILED, result.message()));
                }
            } catch (Exception exception) {
                results.add(new ImageImporter.Result(sourceName, null, ImageImporter.Status.FAILED,
                        exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
            }
            completed++;
            state.set(new State(Phase.IMPORTING, completed, files.size(), sourceName, null, null));
        }
        return new ImageImporter.Report(results);
    }

    private void finishBatch(ImageImporter.Report report, Throwable error) {
        if (error != null) {
            fail(report, error);
            return;
        }
        // The authoritative sync handler already schedules one client resource reload.
        state.set(new State(Phase.FINISHED, report.results().size(), report.results().size(), "", report, null));
    }

    public boolean rename(int slot, String name) {
        return simpleOperation(Phase.RELOADING, new PaintingPayloads.Rename(requestIds.incrementAndGet(), slot, name));
    }

    public boolean resize(int slot, PaintingSizes.Size size) {
        return simpleOperation(Phase.RELOADING, new PaintingPayloads.Resize(
                requestIds.incrementAndGet(), slot, size.width(), size.height()));
    }

    public boolean remove(int slot) {
        return simpleOperation(Phase.REMOVING, new PaintingPayloads.Remove(requestIds.incrementAndGet(), slot));
    }

    private boolean simpleOperation(Phase phase, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        if (!library.canEdit()) {
            return false;
        }
        synchronized (lock) {
            if (state().busy()) {
                return false;
            }
            state.set(new State(phase, 0, 1, "", null, null));
        }
        CompletableFuture.supplyAsync(() -> request(payload), Util.ioPool())
                .whenCompleteAsync((result, error) -> {
                    if (error != null) {
                        fail(null, error);
                    } else if (!result.success()) {
                        state.set(new State(Phase.FAILED, 1, 1, "", null, result.message()));
                    } else {
                        // The authoritative sync handler already schedules one client resource reload.
                        state.set(new State(Phase.FINISHED, 1, 1, "", null, null));
                    }
                }, Minecraft.getInstance());
        return true;
    }

    private PaintingPayloads.Result request(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        long id = switch (payload) {
            case PaintingPayloads.Upload upload -> upload.requestId();
            case PaintingPayloads.Rename rename -> rename.requestId();
            case PaintingPayloads.Resize resize -> resize.requestId();
            case PaintingPayloads.Remove remove -> remove.requestId();
            default -> throw new IllegalArgumentException("Unsupported request");
        };
        CompletableFuture<PaintingPayloads.Result> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            ClientPlayNetworking.send(payload);
            return future.orTimeout(90, TimeUnit.SECONDS).join();
        } catch (RuntimeException exception) {
            pending.remove(id);
            throw exception;
        }
    }

    public boolean reloadPaintings() {
        synchronized (lock) {
            if (state().busy()) {
                return false;
            }
            state.set(new State(Phase.RELOADING, 0, 0, "", null, null));
        }
        Minecraft.getInstance().reloadResourcePacks().whenCompleteAsync((ignored, error) -> {
            if (error != null) {
                fail(null, error);
            } else {
                state.set(new State(Phase.FINISHED, 0, 0, "", null, null));
            }
        }, Minecraft.getInstance());
        return true;
    }

    private void fail(ImageImporter.Report report, Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        CustomPaintingsMod.LOGGER.error("Custom Paintings operation failed", cause);
        state.set(new State(Phase.FAILED, report == null ? 0 : report.results().size(),
                report == null ? 0 : report.results().size(), "", report, message));
    }

    private static String defaultName(String fileName) {
        String stem = fileName.replaceFirst("(?i)\\.(png|jpg|jpeg)$", "").replace('_', ' ').strip();
        if (stem.isEmpty()) {
            stem = "Custom Painting";
        }
        return stem.length() > 48 ? stem.substring(0, 48) : stem;
    }
}
