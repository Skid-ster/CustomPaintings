package com.jay.custompaintings.client;

import com.jay.custompaintings.library.PaintingEdit;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-side staging area for library edits. Moving a slider, changing fit, renaming, or
 * marking a painting for removal only changes this draft. Nothing is sent to the server and
 * no resources reload until the player explicitly presses Apply.
 *
 * <p>Drafts are rebased onto authoritative syncs. If another player (or an import/remove)
 * changes the same slot while a draft is open, the stale draft is dropped instead of being
 * applied over newer data. The UI can show {@link #conflictCount()} after a sync.</p>
 */
public final class PaintingDrafts {
    private final Map<Integer, PaintingEntry> edited = new LinkedHashMap<>();
    private final Set<Integer> removed = new LinkedHashSet<>();
    private long baseRevision = -1L;
    private int conflictCount;

    public synchronized void stage(PaintingEntry entry) {
        removed.remove(entry.slot());
        edited.put(entry.slot(), entry);
    }

    public synchronized void markRemoved(int slot) {
        edited.remove(slot);
        removed.add(slot);
    }

    public synchronized void discard() {
        edited.clear();
        removed.clear();
        baseRevision = -1L;
        conflictCount = 0;
    }

    public synchronized void discardSlot(int slot) {
        edited.remove(slot);
        removed.remove(slot);
    }

    public synchronized boolean isRemoved(int slot) {
        return removed.contains(slot);
    }

    public synchronized boolean hasStagedEdit(int slot) {
        return edited.containsKey(slot);
    }

    /** Entry the UI should display: staged metadata wins over the last applied snapshot. */
    public synchronized PaintingEntry displayEntry(int slot, PaintingLibrary current) {
        PaintingEntry draft = edited.get(slot);
        return draft != null ? draft : current.get(slot).orElse(null);
    }

    public synchronized boolean hasChanges(PaintingLibrary current) {
        return !changes(current).isEmpty() || !removals(current).isEmpty();
    }

    public synchronized int changeCount(PaintingLibrary current) {
        return changes(current).size() + removals(current).size();
    }

    public synchronized List<PaintingEdit> changes(PaintingLibrary current) {
        List<PaintingEdit> changes = new ArrayList<>();
        for (PaintingEntry draft : edited.values()) {
            PaintingEntry applied = current.get(draft.slot()).orElse(null);
            if (applied != null && !applied.equals(draft) && !removed.contains(draft.slot())) {
                changes.add(PaintingEdit.from(draft));
            }
        }
        return List.copyOf(changes);
    }

    public synchronized List<Integer> removals(PaintingLibrary current) {
        List<Integer> removals = new ArrayList<>();
        for (int slot : removed) {
            if (current.isOccupied(slot)) {
                removals.add(slot);
            }
        }
        return List.copyOf(removals);
    }

    /** Rebase drafts onto an authoritative snapshot, dropping drafts whose slot/image disappeared. */
    public synchronized void onSync(PaintingLibrary current) {
        baseRevision = current.revision();
        int conflicts = 0;
        for (var iterator = edited.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            PaintingEntry applied = current.get(entry.getKey()).orElse(null);
            if (applied == null || !applied.sha256().equals(entry.getValue().sha256())) {
                conflicts++;
                iterator.remove();
            } else if (applied.equals(entry.getValue())) {
                // The draft reached the server (ours or someone else's identical edit).
                iterator.remove();
            }
        }
        removed.removeIf(slot -> !current.isOccupied(slot));
        conflictCount += conflicts;
    }

    public synchronized long baseRevision() {
        return baseRevision;
    }

    public synchronized int conflictCount() {
        return conflictCount;
    }

    public synchronized void clearConflicts() {
        conflictCount = 0;
    }
}
