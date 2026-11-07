package com.jay.custompaintings.client;

import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;
import com.jay.custompaintings.network.PaintingPayloads;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Last synchronized server library. Images stay in memory until the next atlas reload. */
public final class ClientPaintingLibrary {
    private volatile PaintingLibrary library = PaintingLibrary.EMPTY;
    private volatile Map<Integer, byte[]> images = Map.of();
    private volatile boolean canEdit;
    private volatile boolean synced;
    private volatile long generation;

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

    public Optional<byte[]> image(int slot) {
        return Optional.ofNullable(images.get(slot));
    }

    /** Returns false for duplicate syncs so join/channel-registration/mutation events do not reload repeatedly. */
    public boolean apply(PaintingPayloads.Sync sync) {
        PaintingLibrary updatedLibrary = sync.library();
        if (synced && canEdit == sync.canEdit() && library.equals(updatedLibrary)) {
            return false;
        }
        Map<Integer, byte[]> updated = new HashMap<>();
        for (PaintingPayloads.EntryData entry : sync.entries()) {
            updated.put(entry.slot(), entry.png());
        }
        library = updatedLibrary;
        images = Map.copyOf(updated);
        canEdit = sync.canEdit();
        synced = true;
        generation++;
        return true;
    }

    public void clear() {
        library = PaintingLibrary.EMPTY;
        images = Map.of();
        canEdit = false;
        synced = false;
        generation++;
    }

    public Optional<PaintingEntry> entry(int slot) {
        return library.get(slot);
    }
}
