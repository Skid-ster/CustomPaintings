package com.jay.custompaintings;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Common entrypoint. Rendering and image loading live in the client entrypoint. */
public final class CustomPaintingsMod implements ModInitializer {
    public static final String MOD_ID = "custompaintings";
    public static final Logger LOGGER = LoggerFactory.getLogger("Custom Paintings");

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static Path imagesDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("images");
    }

    @Override
    public void onInitialize() {
        try {
            Files.createDirectories(imagesDirectory());
        } catch (IOException exception) {
            LOGGER.warn("Could not create the image folder; vanilla paintings will remain unchanged", exception);
        }
        LOGGER.info("Custom Paintings initialized. Upload PNG/JPG images through Mod Menu, or put them in {} and reload resources.", imagesDirectory());
    }
}
