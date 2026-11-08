package com.jay.custompaintings.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jay.custompaintings.CustomPaintingsMod;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side settings. The Painting Menu is open to every player by default, in Survival as
 * well as Creative, because it replaces crafting once the wood/dye catalogue is exhausted.
 * Operators who want it locked down can set {@code menuAllowedForEveryone} to false.
 */
public final class PaintingConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final boolean menuAllowedForEveryone;

    private PaintingConfig(boolean menuAllowedForEveryone) {
        this.menuAllowedForEveryone = menuAllowedForEveryone;
    }

    public static PaintingConfig defaults() {
        return new PaintingConfig(true);
    }

    public boolean menuAllowedForEveryone() {
        return menuAllowedForEveryone;
    }

    public static PaintingConfig load(Path directory) {
        Path file = directory.resolve("config.json");
        if (!Files.isRegularFile(file)) {
            PaintingConfig config = defaults();
            config.save(directory);
            return config;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            boolean allowed = !json.has("menuAllowedForEveryone") || json.get("menuAllowedForEveryone").getAsBoolean();
            return new PaintingConfig(allowed);
        } catch (IOException | RuntimeException exception) {
            CustomPaintingsMod.LOGGER.warn("Could not read config.json; using defaults", exception);
            return defaults();
        }
    }

    private void save(Path directory) {
        try {
            Files.createDirectories(directory);
            JsonObject json = new JsonObject();
            json.addProperty("menuAllowedForEveryone", menuAllowedForEveryone);
            try (Writer writer = Files.newBufferedWriter(directory.resolve("config.json"), StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
        } catch (IOException exception) {
            CustomPaintingsMod.LOGGER.warn("Could not write config.json", exception);
        }
    }
}
