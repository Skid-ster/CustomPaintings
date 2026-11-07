package com.jay.custompaintings.image;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageAssignmentTest {
    private static final List<ImageAssignment.Target> TARGETS = List.of(
            new ImageAssignment.Target("minecraft:kebab", 16, 16),
            new ImageAssignment.Target("minecraft:aztec", 16, 16),
            new ImageAssignment.Target("minecraft:pointer", 64, 64));

    @Test
    void exactNamesTakePriorityAndGenericImagesFillRemainingCanvases() {
        var result = ImageAssignment.assign(List.of("sunset.jpg", "kebab.png"), TARGETS);
        assertEquals("minecraft:kebab", result.get("kebab.png").getFirst().targetId());
        assertEquals(List.of("minecraft:aztec", "minecraft:pointer"),
                result.get("sunset.jpg").stream().map(ImageAssignment.Assignment::targetId).toList());
        assertEquals(64, result.get("sunset.jpg").getLast().width());
    }

    @Test
    void oneImageReplacesEveryCanvas() {
        var result = ImageAssignment.assign(List.of("holiday.JPG"), TARGETS);
        assertEquals(3, result.get("holiday.JPG").size());
    }

    @Test
    void exactOnlyImagesAlsoFillUnmatchedCanvases() {
        var result = ImageAssignment.assign(List.of("kebab.png"), TARGETS);
        assertEquals(3, result.get("kebab.png").size());
        assertEquals("minecraft:kebab", result.get("kebab.png").getFirst().targetId());
    }

    @Test
    void assignmentIsIndependentOfDirectoryIterationOrder() {
        assertEquals(ImageAssignment.assign(List.of("a.png", "b.jpg"), TARGETS),
                ImageAssignment.assign(List.of("b.jpg", "a.png"), TARGETS));
    }

    @Test
    void extraImagesRemainListedWithoutInventingNewPaintingVariants() {
        var result = ImageAssignment.assign(List.of("a.png", "b.png", "c.png", "d.png"), TARGETS);
        assertEquals(4, result.size());
        assertTrue(result.values().stream().anyMatch(List::isEmpty));
        assertEquals(3, result.values().stream().filter(list -> !list.isEmpty()).count());
    }

    @Test
    void namespaceQualifiedFileNamesCanTargetVanillaPaintings() {
        var result = ImageAssignment.assign(List.of("minecraft_kebab.png", "other.jpg"), TARGETS);
        assertEquals("minecraft:kebab", result.get("minecraft_kebab.png").getFirst().targetId());
    }

    @Test
    void emptyInputsAreSafe() {
        assertTrue(ImageAssignment.assign(List.of(), TARGETS).isEmpty());
        assertEquals(List.of(), ImageAssignment.assign(List.of("a.png"), List.of()).get("a.png"));
    }
}
