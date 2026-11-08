package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;
import com.jay.custompaintings.network.PaintingPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Last synchronized server library, scaled to an unlimited number of paintings.
 *
 * <p>Metadata arrives in pages and is applied only once a revision is complete. Master PNGs
 * are fetched lazily per id and kept in a bounded LRU memory cache backed by an on-disk cache
 * keyed by SHA-256, so revisiting a world does not re-download artwork.</p>
 */
public final class ClientPaintingLibrary {
    /** Bounds resident image bytes; artwork is re-fetched or re-read from disk on demand. */
    private static final int MAX_CACHED_IMAGES = 96;

    private final Path cacheDirectory;
    private volatile PaintingLibrary library = PaintingLibrary.EMPTY;
    private volatile boolean canEdit;
    private volatile boolean synced;
    private volatile long generation;

    private final Map<Integer, byte[]> images = new LinkedHashMap<>(16, 0.75F, true);
    private final Set<Integer> requested = new HashSet<>();
    private final Set<Integer> unavailable = new HashSet<>();
    private final Map<Integer, PaintingPayloads.Assembler> transfers = new HashMap<>();

    private long pendingRevision = -1;
    private long pendingSeed;
    private int pendingNextId;
    private boolean pendingCanEdit;
    private int pendingTotal;
    private int pendingPageCount;
    private final Map<Integer, List<PaintingPayloads.EntryData>> pendingPages = new HashMap<>();

    public ClientPaintingLibrary(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    public PaintingLibrary library() {
        return library;
    }

    public boolean canEdit() {
        return canEdit;
    }

    public boolean synced() {
        return synced;
    }

    public long generation() {
        return generation;
    }

    public Optional<PaintingEntry> entry(int id) {
        return library.get(id);
    }

    /** Applies one metadata page; returns true only when a whole revision became complete. */
    public synchronized boolean applyPage(PaintingPayloads.SyncPage page) {
        if (synced && page.revision() < library.revision()) {
            return false;
        }
        if (page.pageCount() <= 0 || page.page() < 0 || page.page() >= page.pageCount()) {
            return false;
        }
        if (page.revision() != pendingRevision) {
            pendingRevision = page.revision();
            pendingSeed = page.recipeSeed();
            pendingNextId = page.nextId();
            pendingCanEdit = page.canEdit();
            pendingTotal = page.total();
            pendingPageCount = page.pageCount();
            pendingPages.clear();
        }
        if (page.total() != pendingTotal || page.pageCount() != pendingPageCount) {
            return false;
        }
        pendingPages.put(page.page(), page.entries());
        if (pendingPages.size() < pendingPageCount) {
            return false;
        }
        List<PaintingEntry> entries = new ArrayList<>();
        try {
            for (int index = 0; index < pendingPageCount; index++) {
                for (PaintingPayloads.EntryData data : pendingPages.get(index)) {
                    entries.add(data.entry());
                }
            }
        } catch (RuntimeException exception) {
            CustomPaintingsMod.LOGGER.warn("Ignoring malformed painting library page", exception);
            pendingPages.clear();
            pendingRevision = -1;
            return false;
        }
        PaintingLibrary updated;
        try {
            updated = new PaintingLibrary(pendingRevision, pendingSeed, entries, pendingNextId);
        } catch (RuntimeException exception) {
            CustomPaintingsMod.LOGGER.warn("Ignoring inconsistent painting library", exception);
            pendingPages.clear();
            pendingRevision = -1;
            return false;
        }
        boolean unchanged = synced && canEdit == pendingCanEdit && library.equals(updated);
        pendingPages.clear();
        pendingRevision = -1;
        if (unchanged) {
            return false;
        }
        library = updated;
        canEdit = pendingCanEdit;
        synced = true;
        generation++;
        // Forget caches for paintings that vanished or whose pixels changed.
        Set<Integer> live = new HashSet<>();
        for (PaintingEntry entry : updated.entries()) {
            live.add(entry.id());
        }
        images.keySet().retainAll(live);
        requested.retainAll(live);
        unavailable.retainAll(live);
        transfers.keySet().retainAll(live);
        return true;
    }

    /** Cached master PNG bytes, or empty if not resident. Does not trigger a request. */
    public synchronized Optional<byte[]> image(int id) {
        return Optional.ofNullable(images.get(id));
    }

    /**
     * Returns cached pixels, requesting them from the server (or reading the disk cache) on
     * first use. Safe to call every frame from renderers and screens.
     */
    public synchronized Optional<byte[]> imageOrRequest(int id) {
        byte[] cached = images.get(id);
        if (cached != null) {
            return Optional.of(cached);
        }
        PaintingEntry entry = library.get(id).orElse(null);
        if (entry == null || unavailable.contains(id) || transfers.containsKey(id)) {
            return Optional.empty();
        }
        byte[] disk = readDiskCache(entry.sha256());
        if (disk != null) {
            store(id, disk);
            return Optional.of(disk);
        }
        if (requested.add(id) && ClientPlayNetworking.canSend(PaintingPayloads.ImageRequest.TYPE)) {
            ClientPlayNetworking.send(new PaintingPayloads.ImageRequest(id));
        }
        return Optional.empty();
    }

    /** Server announced an image; {@code totalBytes == 0} marks it permanently unavailable. */
    public synchronized void beginImage(PaintingPayloads.ImageStart start) {
        if (start.totalBytes() <= 0) {
            unavailable.add(start.id());
            transfers.remove(start.id());
            return;
        }
        if (start.totalBytes() > PaintingPayloads.MAX_IMAGE_BYTES) {
            return;
        }
        try {
            transfers.put(start.id(), new PaintingPayloads.Assembler(start.sha256(), start.totalBytes()));
        } catch (RuntimeException exception) {
            unavailable.add(start.id());
        }
    }

    /** Accepts one chunk; returns the painting id once its image is complete. */
    public synchronized Optional<Integer> acceptChunk(PaintingPayloads.ImageChunk chunk) {
        PaintingPayloads.Assembler assembler = transfers.get((int) chunk.transferId());
        if (assembler == null) {
            return Optional.empty();
        }
        assembler.accept(chunk.index(), chunk.data());
        if (!assembler.complete()) {
            return Optional.empty();
        }
        int id = (int) chunk.transferId();
        byte[] png = assembler.finish();
        transfers.remove(id);
        store(id, png);
        writeDiskCache(assembler.sha256(), png);
        return Optional.of(id);
    }

    private void store(int id, byte[] png) {
        images.put(id, png);
        while (images.size() > MAX_CACHED_IMAGES) {
            Integer eldest = images.keySet().iterator().next();
            images.remove(eldest);
            // Allow a later re-request after eviction.
            requested.remove(eldest);
        }
    }

    private Path cachePath(String sha256) {
        return cacheDirectory.resolve(sha256 + ".png");
    }

    private byte[] readDiskCache(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            return null;
        }
        Path path = cachePath(sha256);
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                return null;
            }
            if (Files.size(path) > PaintingPayloads.MAX_IMAGE_BYTES) {
                return null;
            }
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            return null;
        }
    }

    private void writeDiskCache(String sha256, byte[] png) {
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            return;
        }
        try {
            Files.createDirectories(cacheDirectory);
            Path target = cachePath(sha256);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            Path staged = Files.createTempFile(cacheDirectory, ".img-", ".tmp");
            try {
                Files.write(staged, png);
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(staged);
            }
        } catch (IOException exception) {
            CustomPaintingsMod.LOGGER.debug("Could not cache painting image", exception);
        }
    }

    /** Forces a fresh fetch, e.g. after the server replaced a painting's pixels. */
    public synchronized void invalidate(int id) {
        images.remove(id);
        requested.remove(id);
        unavailable.remove(id);
        transfers.remove(id);
    }

    public synchronized void clear() {
        library = PaintingLibrary.EMPTY;
        images.clear();
        requested.clear();
        unavailable.clear();
        transfers.clear();
        pendingPages.clear();
        pendingRevision = -1;
        canEdit = false;
        synced = false;
        generation++;
    }
}
