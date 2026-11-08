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
 * drafts whose own slot changed incompatibly; unrelated edits keep their drafts. A successful
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
        PaintingEntry base = current.get(draft.slot())
                .orElseThrow(() -> new IllegalArgumentException("Cannot edit an empty painting slot"));
        if (!base.sha256().equals(draft.sha256())) {
            throw new IllegalArgumentException("Draft image does not match the applied painting");
        }
        bases.putIfAbsent(draft.slot(), base);
        removed.remove(draft.slot());
        edited.put(draft.slot(), draft);
    }

    public synchronized void markRemoved(int slot, PaintingLibrary current) {
        PaintingEntry base = current.get(slot)
                .orElseThrow(() -> new IllegalArgumentException("Cannot remove an empty painting slot"));
        bases.putIfAbsent(slot, base);
        edited.remove(slot);
        removed.add(slot);
    }

    public synchronized void discard() {
        edited.clear();
        bases.clear();
        removed.clear();
        baseRevision = -1L;
        conflictCount = 0;
    }

    public synchronized void discardSlot(int slot) {
        edited.remove(slot);
        bases.remove(slot);
        removed.remove(slot);
    }

    public synchronized boolean isRemoved(int slot) {
        return removed.contains(slot);
    }

    public synchronized boolean hasStagedEdit(int slot) {
        return edited.containsKey(slot);
    }

    public synchronized boolean hasSlotChanges(int slot, PaintingLibrary current) {
        return removed.contains(slot) && current.isOccupied(slot)
                || edited.containsKey(slot) && current.get(slot).map(entry -> !entry.equals(edited.get(slot))).orElse(false);
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

    /** Rebase onto an authoritative snapshot without silently overwriting another player's edit. */
    public synchronized void onSync(PaintingLibrary current) {
        baseRevision = current.revision();
        int conflicts = 0;
        for (var iterator = edited.entrySet().iterator(); iterator.hasNext(); ) {
            var entry = iterator.next();
            int slot = entry.getKey();
            PaintingEntry draft = entry.getValue();
            PaintingEntry applied = current.get(slot).orElse(null);
            PaintingEntry base = bases.get(slot);
            if (applied == null) {
                conflicts++;
                iterator.remove();
                bases.remove(slot);
            } else if (applied.equals(draft)) {
                // The draft reached the server (ours or someone else's identical edit).
                iterator.remove();
                bases.remove(slot);
            } else if (base == null || !applied.equals(base)) {
                // Even a name/size/fit-only change by someone else is a conflict, not just a new image.
                conflicts++;
                iterator.remove();
                bases.remove(slot);
            }
        }
        for (var iterator = removed.iterator(); iterator.hasNext(); ) {
            int slot = iterator.next();
            PaintingEntry applied = current.get(slot).orElse(null);
            PaintingEntry base = bases.get(slot);
            if (applied == null) {
                iterator.remove();
                bases.remove(slot);
            } else if (base == null || !applied.equals(base)) {
                conflicts++;
                iterator.remove();
                bases.remove(slot);
            }
        }
        conflictCount += conflicts;
    }

    /** Clear only the exact drafts submitted by a successful Apply; keep anything staged afterward. */
    public synchronized void acknowledgeApplied(List<PaintingEdit> appliedEdits, List<Integer> appliedRemovals) {
        for (PaintingEdit edit : appliedEdits) {
            PaintingEntry draft = edited.get(edit.slot());
            if (draft != null && PaintingEdit.from(draft).equals(edit)) {
                edited.remove(edit.slot());
                bases.remove(edit.slot());
            }
        }
        for (int slot : appliedRemovals) {
            if (removed.remove(slot)) {
                bases.remove(slot);
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
