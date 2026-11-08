package com.jay.custompaintings.library;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards the size-only variant catalogue and the wood/dye recipe catalogue. There are no
 * per-painting assets any more: artwork is a dynamic texture keyed by painting id.
 */
class PaintingAssetIdsTest {
    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    void exactlySixtyFourSizeVariantsShareOnePlaceholderAsset() throws Exception {
        Path variants = RESOURCES.resolve("resourcepacks/library/data/custompaintings/painting_variant");
        Set<String> expected = new HashSet<>();
        for (PaintingSizes.Size size : PaintingSizes.PRESETS) {
            String id = "canvas_" + size.width() + "x" + size.height();
            expected.add(id + ".json");
            Path file = variants.resolve(id + ".json");
            assertTrue(Files.isRegularFile(file), file.toString());
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertEquals("custompaintings:canvas", json.get("asset_id").getAsString());
            assertEquals(size.width(), json.get("width").getAsInt());
            assertEquals(size.height(), json.get("height").getAsInt());
        }
        try (var files = Files.list(variants)) {
            assertEquals(expected, files.map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet()));
        }
        assertTrue(Files.isRegularFile(RESOURCES.resolve("assets/custompaintings/textures/painting/canvas.png")));
    }

    @Test
    void oneItemModelBacksEveryPainting() throws Exception {
        Path items = RESOURCES.resolve("assets/custompaintings/items");
        try (var files = Files.list(items)) {
            assertEquals(Set.of("painting.json"), files.map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet()));
        }
        var itemJson = JsonParser.parseString(Files.readString(items.resolve("painting.json"))).getAsJsonObject();
        assertEquals("custompaintings:item/painting", itemJson.getAsJsonObject("model").get("model").getAsString());
    }

    @Test
    void recipeFilesCoverTheWholeCatalogueExactlyOnce() throws Exception {
        Path recipes = RESOURCES.resolve("resourcepacks/library/data/custompaintings/recipe");
        Set<String> expected = new HashSet<>();
        for (int index = 0; index < RecipeCatalog.RECIPES.size(); index++) {
            RecipeCatalog.EasyRecipe recipe = RecipeCatalog.RECIPES.get(index);
            String file = RecipeCatalog.recipePath(index) + ".json";
            expected.add(file);
            var json = JsonParser.parseString(Files.readString(recipes.resolve(file))).getAsJsonObject();
            assertEquals("custompaintings:painting_recipe", json.get("type").getAsString());
            assertEquals(recipe.id(), json.get("recipe").getAsString());
            assertEquals(2, recipe.ingredients().size());
            for (String ingredient : recipe.ingredients()) {
                assertTrue(ingredient.startsWith("minecraft:"), ingredient);
            }
        }
        assertEquals(192, RecipeCatalog.size());
        try (var files = Files.list(recipes)) {
            assertEquals(expected, files.map(path -> path.getFileName().toString())
                    .collect(java.util.stream.Collectors.toSet()));
        }
    }
}
