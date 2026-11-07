package com.jay.custompaintings.library;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Guards the exact sprite ids consumed by Minecraft's painting renderer and item models. */
class PaintingAssetIdsTest {
    private static final Path RESOURCES = Path.of("src", "main", "resources");

    @Test
    void everyVariantAssetIdMatchesTheInjectedPaintingSpriteId() throws Exception {
        Path variants = RESOURCES.resolve("resourcepacks/library/data/custompaintings/painting_variant");
        int checked = 0;
        for (int slot = 0; slot < 10; slot++) {
            for (PaintingSizes.Size size : PaintingSizes.PRESETS) {
                String id = "slot_" + slot + "_" + size.width() + "x" + size.height();
                Path file = variants.resolve(id + ".json");
                assertTrue(Files.isRegularFile(file), file.toString());
                var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                assertEquals("custompaintings:" + id, json.get("asset_id").getAsString());
                assertEquals(size.width(), json.get("width").getAsInt());
                assertEquals(size.height(), json.get("height").getAsInt());
                checked++;
            }
        }
        assertEquals(640, checked);
    }

    @Test
    void everyItemModelUsesTheInjectedItemSpriteId() throws Exception {
        for (int slot = 0; slot < 10; slot++) {
            Path item = RESOURCES.resolve("assets/custompaintings/items/slot_" + slot + ".json");
            Path model = RESOURCES.resolve("assets/custompaintings/models/item/slot_" + slot + ".json");
            var itemJson = JsonParser.parseString(Files.readString(item)).getAsJsonObject();
            assertEquals("custompaintings:item/slot_" + slot,
                    itemJson.getAsJsonObject("model").get("model").getAsString());
            var modelJson = JsonParser.parseString(Files.readString(model)).getAsJsonObject();
            assertEquals("custompaintings:item/slot_" + slot,
                    modelJson.getAsJsonObject("textures").get("layer0").getAsString());
        }
    }
}
