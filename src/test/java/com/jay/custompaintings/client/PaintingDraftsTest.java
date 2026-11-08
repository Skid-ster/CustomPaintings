package com.jay.custompaintings.client;

import com.jay.custompaintings.image.ImageFit;
import com.jay.custompaintings.library.PaintingEdit;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaintingDraftsTest {
    private static PaintingEntry entry(int slot, String name, int width, int height, ImageFit fit, String recipe) {
        return new PaintingEntry(slot, name, width, height, fit, "slot_" + slot + ".png",
                "a".repeat(64), recipe);
    }

    private static PaintingLibrary library(PaintingEntry... entries) {
        return new PaintingLibrary(7, 123, List.of(entries));
    }

    @Test
    void stagingSeveralPaintingsDoesNotChangeTheAppliedLibrary() {
        PaintingEntry first = entry(0, "First", 2, 2, ImageFit.FIT, "paper_black");
        PaintingEntry second = entry(1, "Second", 1, 1, ImageFit.FIT, "paper_blue");
        PaintingLibrary applied = library(first, second);
        PaintingDrafts drafts = new PaintingDrafts();

        drafts.stage(first.withSize(8, 8).withFit(ImageFit.FILL), applied);
        drafts.stage(second.withName("Renamed").withSize(4, 2), applied);

        assertEquals(2, drafts.changeCount(applied));
        assertEquals(List.of(
                new PaintingEdit(0, "First", 8, 8, ImageFit.FILL),
                new PaintingEdit(1, "Renamed", 4, 2, ImageFit.FIT)), drafts.changes(applied));
        assertEquals(first, applied.get(0).orElseThrow());
        assertEquals(second, applied.get(1).orElseThrow());
        assertEquals(8, drafts.displayEntry(0, applied).width());
        assertEquals("Renamed", drafts.displayEntry(1, applied).name());
    }

    @Test
    void discardAndRevertRemovePendingChanges() {
        PaintingEntry first = entry(0, "First", 2, 2, ImageFit.FIT, "paper_black");
        PaintingLibrary applied = library(first);
        PaintingDrafts drafts = new PaintingDrafts();
        drafts.stage(first.withSize(3, 3), applied);
        drafts.markRemoved(0, applied);
        assertTrue(drafts.isRemoved(0));
        assertEquals(List.of(0), drafts.removals(applied));
        assertTrue(drafts.changes(applied).isEmpty());

        drafts.discardSlot(0);
        assertFalse(drafts.hasChanges(applied));
        drafts.stage(first.withSize(4, 4), applied);
        drafts.discard();
        assertFalse(drafts.hasChanges(applied));
    }

    @Test
    void syncDropsStaleDraftsButKeepsCompatibleOnes() {
        PaintingEntry first = entry(0, "First", 2, 2, ImageFit.FIT, "paper_black");
        PaintingEntry second = entry(1, "Second", 1, 1, ImageFit.FIT, "paper_blue");
        PaintingDrafts drafts = new PaintingDrafts();
        PaintingLibrary applied = library(first, second);
        drafts.stage(first.withSize(8, 8), applied);
        drafts.stage(second.withName("Renamed"), applied);

        PaintingEntry replacedSecond = new PaintingEntry(1, "Server", 1, 1, ImageFit.FIT,
                "slot_1.png", "b".repeat(64), "paper_blue");
        PaintingLibrary updated = new PaintingLibrary(8, 123, List.of(first, replacedSecond));
        drafts.onSync(updated);

        assertEquals(1, drafts.changeCount(updated));
        assertEquals(0, drafts.changes(updated).getFirst().slot());
        assertEquals(1, drafts.conflictCount());
        drafts.clearConflicts();
        assertEquals(0, drafts.conflictCount());
    }

    @Test
    void applyingIdenticalServerStateClearsDraftsWithoutConflict() {
        PaintingEntry first = entry(0, "First", 2, 2, ImageFit.FIT, "paper_black");
        PaintingDrafts drafts = new PaintingDrafts();
        drafts.stage(first.withSize(8, 8), library(first));
        PaintingLibrary applied = library(first.withSize(8, 8));
        drafts.onSync(applied);
        assertFalse(drafts.hasChanges(applied));
        assertEquals(0, drafts.conflictCount());
    }

    @Test
    void metadataOnlyServerEditIsAConflictEvenWithTheSameImage() {
        PaintingEntry first = entry(0, "First", 2, 2, ImageFit.FIT, "paper_black");
        PaintingLibrary applied = library(first);
        PaintingDrafts drafts = new PaintingDrafts();
        drafts.stage(first.withName("Local"), applied);

        PaintingLibrary updated = new PaintingLibrary(8, 123, List.of(first.withName("Remote").withSize(4, 4)));
        drafts.onSync(updated);

        assertFalse(drafts.hasChanges(updated));
        assertEquals(1, drafts.conflictCount());
    }

    @Test
    void unrelatedServerEditsKeepCompatibleDrafts() {
        PaintingEntry first = entry(0, "First", 2, 2, ImageFit.FIT, "paper_black");
        PaintingEntry second = entry(1, "Second", 1, 1, ImageFit.FIT, "paper_blue");
        PaintingLibrary applied = library(first, second);
        PaintingDrafts drafts = new PaintingDrafts();
        drafts.stage(first.withName("Local"), applied);

        PaintingLibrary updated = new PaintingLibrary(8, 123, List.of(first, second.withName("Remote")));
        drafts.onSync(updated);

        assertEquals(List.of(new PaintingEdit(0, "Local", 2, 2, ImageFit.FIT)), drafts.changes(updated));
        assertEquals(0, drafts.conflictCount());
    }

    @Test
    void successfulApplyAcknowledgesOnlyTheSubmittedBatch() {
        PaintingEntry first = entry(0, "First", 2, 2, ImageFit.FIT, "paper_black");
        PaintingEntry second = entry(1, "Second", 1, 1, ImageFit.FIT, "paper_blue");
        PaintingLibrary applied = library(first, second);
        PaintingDrafts drafts = new PaintingDrafts();
        PaintingEdit submitted = new PaintingEdit(0, "Applied", 2, 2, ImageFit.FIT);
        drafts.stage(first.withName("Applied"), applied);
        // A newer local draft staged while the request was in flight must survive success.
        drafts.stage(second.withName("Newer"), applied);

        drafts.acknowledgeApplied(List.of(submitted), List.of());

        assertFalse(drafts.hasStagedEdit(0));
        assertEquals(List.of(new PaintingEdit(1, "Newer", 1, 1, ImageFit.FIT)), drafts.changes(applied));
    }

    @Test
    void pendingRemovalConflictsWhenTheSlotWasReplaced() {
        PaintingEntry first = entry(0, "First", 2, 2, ImageFit.FIT, "paper_black");
        PaintingLibrary applied = library(first);
        PaintingDrafts drafts = new PaintingDrafts();
        drafts.markRemoved(0, applied);

        PaintingEntry replaced = new PaintingEntry(0, "Replacement", 2, 2, ImageFit.FIT,
                "slot_0.png", "b".repeat(64), "paper_black");
        PaintingLibrary updated = new PaintingLibrary(8, 123, List.of(replaced));
        drafts.onSync(updated);

        assertTrue(drafts.removals(updated).isEmpty());
        assertEquals(1, drafts.conflictCount());
    }
}
