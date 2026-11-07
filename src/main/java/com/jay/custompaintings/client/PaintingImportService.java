package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.image.ImageFit;
import com.jay.custompaintings.image.ImageImporter;
import com.jay.custompaintings.image.ImageProcessor;
import com.jay.custompaintings.library.PaintingEdit;
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
    private final PaintingDrafts drafts;
    private final AtomicReference<State> state = new AtomicReference<>(State.idle());
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<Long, CompletableFuture<PaintingPayloads.Result>> pending = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public PaintingImportService(ClientPaintingLibrary library, PaintingDrafts drafts) {
        this.library = library;
        this.drafts = drafts;
    }

    public enum Phase { IDLE, IMPORTING, APPLYING, REMOVING, RELOADING, FINISHED, FAILED }

    public record State(Phase phase, int completed, int total, String currentFile,
                        ImageImporter.Report report, String error) {
        private static State idle() {
            return new State(Phase.IDLE, 0, 0, "", null, null);
        }

        public boolean busy() {
            return phase == Phase.IMPORTING || phase == Phase.APPLYING
                    || phase == Phase.REMOVING || phase == Phase.RELOADING;
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
        return importImages(files, null, ImageFit.FIT);
    }

    public boolean importImages(List<Path> files, PaintingSizes.Size size) {
        return importImages(files, size, ImageFit.FIT);
    }

    /** A null size chooses the closest supported canvas for each image's own proportions. */
    public boolean importImages(List<Path> files, PaintingSizes.Size size, ImageFit fit) {
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
        CompletableFuture.supplyAsync(() -> uploadBatch(copy, size, fit), Util.ioPool())
                .whenCompleteAsync((report, error) -> finishBatch(report, error), minecraft);
        return true;
    }

    private ImageImporter.Report uploadBatch(List<Path> files, PaintingSizes.Size size, ImageFit fit) {
        List<ImageImporter.Result> results = new ArrayList<>();
        List<PaintingPayloads.UploadData> uploads = new ArrayList<>();
        List<String> uploadNames = new ArrayList<>();
        List<String> uploadSources = new ArrayList<>();
        int completed = 0;
        for (Path file : files) {
            String sourceName = file.getFileName().toString();
            state.set(new State(Phase.IMPORTING, completed, files.size(), sourceName, null, null));
            try {
                BufferedImage image = ImageProcessor.read(file);
                byte[] png = PaintingLibraryStore.encodePng(ImageProcessor.prepareMaster(image));
                PaintingSizes.Size target = size == null ? PaintingSizes.auto(image.getWidth(), image.getHeight()) : size;
                String name = defaultName(sourceName);
                uploads.add(new PaintingPayloads.UploadData(name, target.width(), target.height(), fit.id(), png));
                uploadNames.add(name);
                uploadSources.add(sourceName);
            } catch (Exception exception) {
                results.add(new ImageImporter.Result(sourceName, null, ImageImporter.Status.FAILED,
                        exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
            }
            completed++;
            state.set(new State(Phase.IMPORTING, completed, files.size(), sourceName, null, null));
        }
        if (!uploads.isEmpty()) {
            // One request for the whole selected batch: one server mutation, one manifest save,
            // one authoritative sync, and one coalesced client atlas reload.
            PaintingPayloads.Result result = request(new PaintingPayloads.UploadBatch(requestIds.incrementAndGet(), uploads));
            for (int i = 0; i < uploads.size(); i++) {
                String sourceName = uploadSources.get(i);
                if (result.success()) {
                    results.add(new ImageImporter.Result(sourceName, uploadNames.get(i),
                            ImageImporter.Status.IMPORTED, result.message()));
                } else {
                    results.add(new ImageImporter.Result(sourceName, null,
                            ImageImporter.Status.FAILED, result.message()));
                }
            }
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
        return library.entry(slot)
                .map(entry -> adjust(slot, entry.name(), size, entry.fit()))
                .orElse(false);
    }

    public boolean setFit(int slot, ImageFit fit) {
        return library.entry(slot)
                .map(entry -> adjust(slot, entry.name(), PaintingSizes.of(entry.width(), entry.height()), fit))
                .orElse(false);
    }

    public boolean adjust(int slot, String name, PaintingSizes.Size size, ImageFit fit) {
        return simpleOperation(Phase.RELOADING, new PaintingPayloads.Adjust(
                requestIds.incrementAndGet(), slot, name, size.width(), size.height(), fit.id()));
    }

    public boolean remove(int slot) {
        return simpleOperation(Phase.REMOVING, new PaintingPayloads.Remove(requestIds.incrementAndGet(), slot));
    }

    /** Apply every staged draft edit/removal together. Nothing is sent until this is called. */
    public boolean applyDrafts() {
        var current = library.library();
        List<PaintingEdit> edits = drafts.changes(current);
        List<Integer> removals = drafts.removals(current);
        if (edits.isEmpty() && removals.isEmpty()) {
            return false;
        }
        List<PaintingPayloads.EditData> data = edits.stream().map(PaintingPayloads.EditData::from).toList();
        return simpleOperation(Phase.APPLYING, new PaintingPayloads.ApplyBatch(
                requestIds.incrementAndGet(), current.revision(), data, removals), drafts::discard);
    }

    public boolean applyDrafts(List<PaintingEdit> edits, List<Integer> removals) {
        if (edits.isEmpty() && removals.isEmpty()) {
            return false;
        }
        List<PaintingPayloads.EditData> data = edits.stream().map(PaintingPayloads.EditData::from).toList();
        return simpleOperation(Phase.APPLYING, new PaintingPayloads.ApplyBatch(
                requestIds.incrementAndGet(), library.library().revision(), data, List.copyOf(removals)));
    }

    private boolean simpleOperation(Phase phase, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        return simpleOperation(phase, payload, () -> { });
    }

    private boolean simpleOperation(Phase phase, net.minecraft.network.protocol.common.custom.CustomPacketPayload payload,
                                    Runnable onSuccess) {
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
                        onSuccess.run();
                        // The authoritative sync handler already schedules one client resource reload.
                        state.set(new State(Phase.FINISHED, 1, 1, "", null, null));
                    }
                }, Minecraft.getInstance());
        return true;
    }

    private PaintingPayloads.Result request(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        long id = switch (payload) {
            case PaintingPayloads.Upload upload -> upload.requestId();
            case PaintingPayloads.UploadBatch upload -> upload.requestId();
            case PaintingPayloads.ApplyBatch apply -> apply.requestId();
            case PaintingPayloads.Rename rename -> rename.requestId();
            case PaintingPayloads.Adjust adjust -> adjust.requestId();
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
