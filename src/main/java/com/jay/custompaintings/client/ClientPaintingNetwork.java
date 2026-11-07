package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.client.mixin.CreativeModeTabsMixin;
import com.jay.custompaintings.library.PaintingLibraryAccess;
import com.jay.custompaintings.network.PaintingPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Applies authoritative library snapshots and refreshes creative tabs/textures on the client thread. */
public final class ClientPaintingNetwork {
    private ClientPaintingNetwork() {
    }

    public static void register(ClientPaintingLibrary library, PaintingImportService imports) {
        ClientPlayNetworking.registerGlobalReceiver(PaintingPayloads.Sync.TYPE, (payload, context) -> {
            if (!library.apply(payload)) {
                return;
            }
            PaintingLibraryAccess.set(library::library);
            CreativeModeTabsMixin.custompaintings$setCachedParameters(null);
            CustomPaintingsMod.LOGGER.info("Synced {} custom painting(s), revision {}; reloading atlases",
                    payload.entries().size(), payload.revision());
            Minecraft.getInstance().reloadResourcePacks();
        });
        ClientPlayNetworking.registerGlobalReceiver(PaintingPayloads.Result.TYPE, (payload, context) ->
                imports.complete(payload));
        // Fabric's server JOIN event can run before the client has registered its play channels.
        // Request an authoritative snapshot as soon as the client play listener can send packets.
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) ->
                ClientPlayNetworking.send(new PaintingPayloads.SyncRequest(PaintingPayloads.SyncRequest.PROTOCOL_VERSION)));
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            library.clear();
            PaintingLibraryAccess.set(library::library);
            CreativeModeTabsMixin.custompaintings$setCachedParameters(null);
        });
    }
}
