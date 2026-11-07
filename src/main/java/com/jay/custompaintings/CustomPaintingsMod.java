package com.jay.custompaintings;

import com.jay.custompaintings.item.CustomPaintingItems;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibraryAccess;
import com.jay.custompaintings.library.PaintingRuntime;
import com.jay.custompaintings.network.PaintingPayloads;
import com.jay.custompaintings.recipe.CustomPaintingRecipe;
import com.jay.custompaintings.server.PaintingServerNetwork;
import com.jay.custompaintings.server.PaintingServerState;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTab;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.pack.PackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Common entrypoint: stable items/variants, authoritative library, recipes and networking. */
public final class CustomPaintingsMod implements ModInitializer {
    public static final String MOD_ID = "custompaintings";
    public static final Logger LOGGER = LoggerFactory.getLogger("Custom Paintings");
    public static final ResourceKey<CreativeModeTab> CUSTOM_TAB =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, id("custom_paintings"));

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static Path configDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
    }

    public static Path imagesDirectory() {
        return configDirectory().resolve("images");
    }

    @Override
    public void onInitialize() {
        try {
            Files.createDirectories(imagesDirectory());
            PaintingServerState.ensureLoaded();
        } catch (IOException exception) {
            LOGGER.error("Could not load the custom painting library; starting empty", exception);
        }
        PaintingLibraryAccess.set(PaintingServerState::library);
        CustomPaintingItems.register();
        Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, id("slot_recipe"), CustomPaintingRecipe.SERIALIZER);
        PaintingPayloads.register();
        PaintingServerNetwork.register();

        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(container ->
                ResourceLoader.registerBuiltinPack(id("library"), "data/custompaintings/datapacks/library",
                        container, Component.translatable("pack.custompaintings.library"), PackActivationType.ALWAYS_ENABLED));

        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, CUSTOM_TAB, FabricCreativeModeTab.builder()
                .title(Component.translatable("itemGroup.custompaintings"))
                .icon(() -> new ItemStack(Items.PAINTING))
                .displayItems((parameters, output) -> {
                    for (PaintingEntry entry : PaintingLibraryAccess.current().entries()) {
                        output.accept(CustomPaintingItems.stack(entry.slot()));
                    }
                })
                .build());
        CreativeModeTabEvents.modifyOutputEvent(ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                Identifier.withDefaultNamespace("functional_blocks"))).register(output -> {
            for (PaintingEntry entry : PaintingLibraryAccess.current().entries()) {
                output.insertAfter(Items.PAINTING, CustomPaintingItems.stack(entry.slot()));
            }
        });
        LOGGER.info("Custom Paintings initialized with {} stable slots and a 10-painting cap", PaintingEntry.MAX_PAINTINGS);
    }
}
