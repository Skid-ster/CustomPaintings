package com.jay.custompaintings.client;

import com.jay.custompaintings.library.PaintingEdit;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Client-side staging area for library edits. Moving a slider, changing fit, renaming, or
 * marking a painting for removal only changes this draft. Nothing is sent to the server and
 * no resources reload until the player explicitly presses Apply.
 *
 * <p>Each draft remembers the applied entry it was based on. Authoritative syncs drop only
 * drafts whose own id changed incompatibly; unrelated edits keep their drafts. A successful
 * Apply acknowledges just the submitted batch, never newer drafts staged while it was busy.</p>
 */
public final class PaintingDrafts {
    private final Map<Integer, PaintingEntry> edited = new LinkedHashMap<>();
    private final Map<Integer, PaintingEntry> bases = new LinkedHashMap<>();
    private final Set<Integer> removed = new LinkedHashSet<>();
    private long baseRevision = -1L;
    private int conflictCount;

    /** Stage against the current authoritative library so metadata conflicts can be detected. */
    public synchronized void stage(PaintingEntry draft, PaintingLibrary current) {
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(current, "current");
        PaintingEntry base = current.get(draft.id())
                .orElseThrow(() -> new IllegalArgumentException("Cannot edit an unknown painting"));
        if (!base.sha256().equals(draft.sha256())) {
            throw new IllegalArgumentException("Draft image does not match the applied painting");
        }
        bases.putIfAbsent(draft.id(), base);
        removed.remove(draft.id());
        edited.put(draft.id(), draft);
    }

    public synchronized void markRemoved(int id, PaintingLibrary current) {
        PaintingEntry base = current.get(id)
                .orElseThrow(() -> new IllegalArgumentException("Cannot remove an unknown painting"));
        bases.putIfAbsent(id, base);
        edited.remove(id);
        removed.add(id);
    }

    public synchronized void discard() {
        edited.clear();
        bases.clear();
        removed.clear();
        baseRevision = -1L;
        conflictCount = 0;
    }

    public synchronized void discardPainting(int id) {
        edited.remove(id);
        bases.remove(id);
        removed.remove(id);
    }

    public synchronized boolean isRemoved(int id) {
        return removed.contains(id);
    }

    public synchronized boolean hasStagedEdit(int id) {
        return edited.containsKey(id);
    }

    public synchronized boolean hasPaintingChanges(int id, PaintingLibrary current) {
        return removed.contains(id) && current.isOccupied(id)
                || edited.containsKey(id) && current.get(id).map(entry -> !entry.equals(edited.get(id))).orElse(false);
    }

    /** Entry the UI should display: staged metadata wins over the last applied snapshot. */
    public synchronized PaintingEntry displayEntry(int id, PaintingLibrary current) {
        PaintingEntry draft = edited.get(id);
        return draft != null ? draft : current.get(id).orElse(null);
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
            PaintingEntry applied = current.get(draft.id()).orElse(null);
            if (applied != null && !applied.equals(draft) && !removed.contains(draft.id())) {
                changes.add(PaintingEdit.from(draft));
            }
        }
        return List.copyOf(changes);
    }

    public synchronized List<Integer> removals(PaintingLibrary current) {
        List<Integer> removals = new ArrayList<>();
        for (int id : removed) {
            if (current.isOccupied(id)) {
                removals.add(id);
            }
        }
        return List.copyOf(removals);
    }

    /** Rebase onto an authoritative snapshot without silently overwriting another player's edit. */
    public synchronized void onSync(PaintingLibrary current) {
        baseRevision = current.revision();
        int conflicts = 0;
        for (var iterator = edited.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            int id = entry.getKey();
            PaintingEntry draft = entry.getValue();
            PaintingEntry applied = current.get(id).orElse(null);
            PaintingEntry base = bases.get(id);
            if (applied == null) {
                conflicts++;
                iterator.remove();
                bases.remove(id);
            } else if (applied.equals(draft)) {
                // The draft reached the server (ours or someone else's identical edit).
                iterator.remove();
                bases.remove(id);
            } else if (base == null || !applied.equals(base)) {
                // Even a name/size/fit-only change by someone else is a conflict, not just a new image.
                conflicts++;
                iterator.remove();
                bases.remove(id);
            }
        }
        for (var iterator = removed.iterator(); iterator.hasNext(); ) {
            int id = iterator.next();
            PaintingEntry applied = current.get(id).orElse(null);
            PaintingEntry base = bases.get(id);
            if (applied == null) {
                iterator.remove();
                bases.remove(id);
            } else if (base == null || !applied.equals(base)) {
                conflicts++;
                iterator.remove();
                bases.remove(id);
            }
        }
        conflictCount += conflicts;
    }

    /** Clear only the exact drafts submitted by a successful Apply; keep anything staged afterward. */
    public synchronized void acknowledgeApplied(List<PaintingEdit> appliedEdits, List<Integer> appliedRemovals) {
        for (PaintingEdit edit : appliedEdits) {
            PaintingEntry draft = edited.get(edit.id());
            if (draft != null && PaintingEdit.from(draft).equals(edit)) {
                edited.remove(edit.id());
                bases.remove(edit.id());
            }
        }
        for (int id : appliedRemovals) {
            if (removed.remove(id)) {
                bases.remove(id);
            }
        }
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
