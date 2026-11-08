package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.library.PaintingLibraryAccess;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Client entrypoint: dynamic artwork textures, the synced library and the Painting Menu.
 *
 * <p>No resource-reload listener is needed any more. Paintings are standalone dynamic textures,
 * so uploads and edits appear immediately without restitching an atlas.</p>
 */
public final class CustomPaintingsClient implements ClientModInitializer {
    private static ClientPaintingLibrary library;
    private static PaintingTextureManager paintings;
    private static PaintingImportService imports;
    private static PaintingDrafts drafts;
    private static KeyMapping openMenuKey;

    public static ClientPaintingLibrary library() {
        return library;
    }

    public static PaintingTextureManager paintings() {
        return paintings;
    }

    public static PaintingImportService imports() {
        return imports;
    }

    public static PaintingDrafts drafts() {
        return drafts;
    }

    @Override
    public void onInitializeClient() {
        library = new ClientPaintingLibrary(CustomPaintingsMod.configDirectory().resolve("cache"));
        PaintingLibraryAccess.set(library::library);
        paintings = new PaintingTextureManager(library);
        drafts = new PaintingDrafts();
        imports = new PaintingImportService(library, drafts);
        ClientPaintingNetwork.register(library, imports, drafts);

        openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.custompaintings.menu", InputConstants.Type.KEYBOARD, InputConstants.KEY_P,
                KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenuKey.consumeClick()) {
                if (client.player != null && client.gui.screen() == null) {
                    openMenu(null);
                }
            }
        });
        // A client-side command works on any server, even where the player cannot use keybinds.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> {
            dispatcher.register(ClientCommands.literal("paintingmenu")
                    .executes(command -> openMenuFromCommand(command.getSource())));
            dispatcher.register(ClientCommands.literal("custompaintings")
                    .executes(command -> openLibraryFromCommand(command.getSource())));
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> paintings.close());
    }

    /** Opens the Painting Menu, preserving the current screen as the return target. */
    public static void openMenu(Screen parent) {
        Minecraft.getInstance().gui.setScreen(new PaintingMenuScreen(parent));
    }

    private static int openMenuFromCommand(FabricClientCommandSource source) {
        Minecraft client = source.getClient();
        // Commands run on the client thread, but defer so the chat screen closes first.
        client.execute(() -> openMenu(null));
        return 1;
    }

    private static int openLibraryFromCommand(FabricClientCommandSource source) {
        Minecraft client = source.getClient();
        client.execute(() -> client.gui.setScreen(new CustomPaintingsScreen(null)));
        return 1;
    }

    /** Used by the library screen's status line. */
    public static Component menuKeyHint() {
        return Component.translatable("menu.custompaintings.keyHint", openMenuKey.getTranslatedKeyMessage());
    }
}
