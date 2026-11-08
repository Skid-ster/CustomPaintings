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
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.util.Util;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One bounded client operation at a time; the server remains authoritative and broadcasts syncs.
 *
 * <p>Images are prepared off-thread and streamed to the server in protocol-sized chunks, then
 * committed as a single atomic library mutation. The library itself has no capacity limit;
 * only the per-batch size is bounded so one operation stays responsive.</p>
 */
public final class PaintingImportService {
    private final ClientPaintingLibrary library;
    private final PaintingDrafts drafts;
    private final AtomicReference<State> state = new AtomicReference<>(State.idle());
    private final AtomicLong requestIds = new AtomicLong();
    private final AtomicLong transferIds = new AtomicLong();
    private final AtomicLong sessions = new AtomicLong();
    private final Map<Long, CompletableFuture<PaintingPayloads.Result>> pending = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public PaintingImportService(ClientPaintingLibrary library, PaintingDrafts drafts) {
        this.library = library;
        this.drafts = drafts;
    }

    public enum Phase { IDLE, IMPORTING, APPLYING, GIVING, FINISHED, FAILED }

    public record State(Phase phase, int completed, int total, String currentFile,
                        ImageImporter.Report report, String error) {
        private static State idle() {
            return new State(Phase.IDLE, 0, 0, "", null, null);
        }

        public boolean busy() {
            return phase == Phase.IMPORTING || phase == Phase.APPLYING || phase == Phase.GIVING;
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
        if (copy.isEmpty() || copy.size() > PaintingPayloads.MAX_BATCH_IMAGES || !library.canEdit()) {
            return false;
        }
        long session;
        synchronized (lock) {
            if (state().busy()) {
                return false;
            }
            session = sessions.get();
            state.set(new State(Phase.IMPORTING, 0, copy.size(), "", null, null));
        }
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> uploadBatch(copy, size, fit, session), Util.ioPool())
                .whenCompleteAsync((report, error) -> {
                    if (session == sessions.get()) {
                        finishBatch(report, error);
                    }
                }, minecraft);
        return true;
    }

    private ImageImporter.Report uploadBatch(List<Path> files, PaintingSizes.Size size, ImageFit fit, long session) {
        ImageImporter.Result[] ordered = new ImageImporter.Result[files.size()];
        List<Long> transfers = new ArrayList<>();
        List<Integer> uploadIndexes = new ArrayList<>();
        List<String> uploadNames = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        int completed = 0;
        for (int i = 0; i < files.size(); i++) {
            if (session != sessions.get()) {
                throw new CancellationException("Disconnected during import");
            }
            Path file = files.get(i);
            String sourceName = fileName(file);
            Path normalized = file.toAbsolutePath().normalize();
            if (!seen.add(normalized)) {
                ordered[i] = new ImageImporter.Result(sourceName, null, ImageImporter.Status.DUPLICATE, null);
            } else {
                try {
                    BufferedImage image = ImageProcessor.read(file);
                    byte[] png = PaintingLibraryStore.encodePng(ImageProcessor.prepareMaster(image));
                    PaintingSizes.Size target = size == null
                            ? PaintingSizes.auto(image.getWidth(), image.getHeight()) : size;
                    String name = defaultName(sourceName);
                    transfers.add(streamImage(name, target, fit, png));
                    uploadIndexes.add(i);
                    uploadNames.add(name);
                } catch (Exception exception) {
                    ordered[i] = new ImageImporter.Result(sourceName, null, ImageImporter.Status.FAILED, message(exception));
                }
            }
            completed++;
            state.set(new State(Phase.IMPORTING, completed, files.size(), sourceName, null, null));
        }
        if (!transfers.isEmpty() && session == sessions.get()) {
            // One commit for the whole batch: one server mutation, one manifest save, one sync.
            PaintingPayloads.Result result = request(new PaintingPayloads.UploadCommit(
                    requestIds.incrementAndGet(), List.copyOf(transfers)));
            for (int i = 0; i < transfers.size(); i++) {
                int index = uploadIndexes.get(i);
                String sourceName = fileName(files.get(index));
                ordered[index] = result.success()
                        ? new ImageImporter.Result(sourceName, uploadNames.get(i), ImageImporter.Status.IMPORTED, result.message())
                        : new ImageImporter.Result(sourceName, null, ImageImporter.Status.FAILED, result.message());
            }
        }
        for (int i = 0; i < ordered.length; i++) {
            if (ordered[i] == null) {
                ordered[i] = new ImageImporter.Result(fileName(files.get(i)), null, ImageImporter.Status.FAILED,
                        "Disconnected before the upload finished");
            }
        }
        return new ImageImporter.Report(List.of(ordered));
    }

    /** Streams one prepared master PNG to the server's staging area in bounded chunks. */
    private long streamImage(String name, PaintingSizes.Size size, ImageFit fit, byte[] png) {
        long transferId = transferIds.incrementAndGet();
        ClientPlayNetworking.send(new PaintingPayloads.UploadStart(transferId, name, size.width(), size.height(),
                fit.id(), sha256(png), png.length));
        for (int index = 0; index < PaintingPayloads.chunkCount(png.length); index++) {
            ClientPlayNetworking.send(new PaintingPayloads.ImageChunk(transferId, index,
                    PaintingPayloads.chunk(png, index)));
        }
        return transferId;
    }

    private void finishBatch(ImageImporter.Report report, Throwable error) {
        if (error != null) {
            fail(report, error);
            return;
        }
        state.set(new State(Phase.FINISHED, report.results().size(), report.results().size(), "", report, null));
    }

    /** Apply every staged draft edit/removal together. Nothing is sent until this is called. */
    public boolean applyDrafts() {
        var current = library.library();
        return applyDrafts(drafts.changes(current), drafts.removals(current));
    }

    public boolean applyDrafts(List<PaintingEdit> edits, List<Integer> removals) {
        List<PaintingEdit> editSnapshot = List.copyOf(edits);
        List<Integer> removalSnapshot = List.copyOf(removals);
        if (editSnapshot.isEmpty() && removalSnapshot.isEmpty()) {
            return false;
        }
        if (editSnapshot.size() + removalSnapshot.size() > PaintingPayloads.MAX_BATCH_CHANGES) {
            state.set(new State(Phase.FAILED, 0, 0, "", null,
                    "Too many staged changes; apply them in smaller batches"));
            return true;
        }
        List<PaintingPayloads.EditData> data = editSnapshot.stream().map(PaintingPayloads.EditData::from).toList();
        return simpleOperation(Phase.APPLYING, new PaintingPayloads.ApplyBatch(
                requestIds.incrementAndGet(), library.library().revision(), data, removalSnapshot),
                () -> drafts.acknowledgeApplied(editSnapshot, removalSnapshot));
    }

    /** Requests a painting from the menu. Works in Survival and Creative alike. */
    public boolean givePainting(int paintingId, int count) {
        long session;
        synchronized (lock) {
            if (state().busy()) {
                return false;
            }
            session = sessions.get();
            state.set(new State(Phase.GIVING, 0, 1, "", null, null));
        }
        CompletableFuture.supplyAsync(() -> request(new PaintingPayloads.GiveRequest(
                requestIds.incrementAndGet(), paintingId, count)), Util.ioPool())
                .whenCompleteAsync((result, error) -> {
                    if (session != sessions.get()) {
                        return;
                    }
                    if (error != null) {
                        fail(null, error);
                    } else if (!result.success()) {
                        state.set(new State(Phase.FAILED, 1, 1, "", null, result.message()));
                    } else {
                        state.set(new State(Phase.FINISHED, 1, 1, "", null, null));
                    }
                }, Minecraft.getInstance());
        return true;
    }

    private boolean simpleOperation(Phase phase, CustomPacketPayload payload, Runnable onSuccess) {
        if (!library.canEdit()) {
            return false;
        }
        long session;
        synchronized (lock) {
            if (state().busy()) {
                return false;
            }
            session = sessions.get();
            state.set(new State(phase, 0, 1, "", null, null));
        }
        CompletableFuture.supplyAsync(() -> request(payload), Util.ioPool())
                .whenCompleteAsync((result, error) -> {
                    if (session != sessions.get()) {
                        return;
                    }
                    if (error != null) {
                        fail(null, error);
                    } else if (!result.success()) {
                        state.set(new State(Phase.FAILED, 1, 1, "", null, result.message()));
                    } else {
                        onSuccess.run();
                        state.set(new State(Phase.FINISHED, 1, 1, "", null, null));
                    }
                }, Minecraft.getInstance());
        return true;
    }

    private PaintingPayloads.Result request(CustomPacketPayload payload) {
        long id = requestId(payload);
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

    /** Cancels in-flight work when the connection ends; late replies cannot update a new session. */
    public void disconnect() {
        List<CompletableFuture<PaintingPayloads.Result>> waiting;
        synchronized (lock) {
            sessions.incrementAndGet();
            waiting = new ArrayList<>(pending.values());
            pending.clear();
            state.set(State.idle());
        }
        CancellationException cancellation = new CancellationException("Disconnected before the server replied");
        for (CompletableFuture<PaintingPayloads.Result> future : waiting) {
            future.completeExceptionally(cancellation);
        }
    }

    /** Re-reads artwork from the server without a full resource reload. */
    public boolean reloadPaintings() {
        synchronized (lock) {
            if (state().busy()) {
                return false;
            }
        }
        for (var entry : library.library().entries()) {
            CustomPaintingsClient.paintings().invalidate(entry.id());
        }
        if (ClientPlayNetworking.canSend(PaintingPayloads.SyncRequest.TYPE)) {
            ClientPlayNetworking.send(new PaintingPayloads.SyncRequest(PaintingPayloads.SyncRequest.PROTOCOL_VERSION));
        }
        state.set(new State(Phase.FINISHED, 0, 0, "", null, null));
        return true;
    }

    private void fail(ImageImporter.Report report, Throwable error) {
        Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                ? error.getCause() : error;
        String message = message(cause);
        CustomPaintingsMod.LOGGER.error("Custom Paintings operation failed", cause);
        state.set(new State(Phase.FAILED, report == null ? 0 : report.results().size(),
                report == null ? 0 : report.results().size(), "", report, message));
    }

    private static long requestId(CustomPacketPayload payload) {
        return switch (payload) {
            case PaintingPayloads.UploadCommit upload -> upload.requestId();
            case PaintingPayloads.ApplyBatch apply -> apply.requestId();
            case PaintingPayloads.GiveRequest give -> give.requestId();
            default -> throw new IllegalArgumentException("Unsupported request");
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String fileName(Path file) {
        return file.getFileName() == null ? file.toString() : file.getFileName().toString();
    }

    private static String message(Throwable exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private static String defaultName(String fileName) {
        String stem = fileName.replaceFirst("(?i)\\.(png|jpg|jpeg)$", "").replace('_', ' ').strip();
        if (stem.isEmpty()) {
            stem = "Custom Painting";
        }
        return stem.length() > 48 ? stem.substring(0, 48) : stem;
    }
}
