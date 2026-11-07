package com.jay.custompaintings.client;

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
            library.apply(payload);
            PaintingLibraryAccess.set(library::library);
            CreativeModeTabsMixin.custompaintings$setCachedParameters(null);
            Minecraft.getInstance().reloadResourcePacks();
        });
        ClientPlayNetworking.registerGlobalReceiver(PaintingPayloads.Result.TYPE, (payload, context) ->
                imports.complete(payload));
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            library.clear();
            PaintingLibraryAccess.set(library::library);
            CreativeModeTabsMixin.custompaintings$setCachedParameters(null);
        });
    }
}
