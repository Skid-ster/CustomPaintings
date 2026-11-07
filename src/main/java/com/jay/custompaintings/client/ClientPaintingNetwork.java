package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.client.mixin.CreativeModeTabsMixin;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;
import com.jay.custompaintings.library.PaintingLibraryAccess;
import com.jay.custompaintings.network.PaintingPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/** Applies authoritative library snapshots and refreshes creative tabs/textures on the client thread. */
public final class ClientPaintingNetwork {
    private static boolean reloadRunning;
    private static boolean reloadPending;

    private ClientPaintingNetwork() {
    }

    public static void register(ClientPaintingLibrary library, PaintingImportService imports, PaintingDrafts drafts) {
        ClientPlayNetworking.registerGlobalReceiver(PaintingPayloads.Sync.TYPE, (payload, context) -> {
            PaintingLibrary before = library.library();
            if (!library.apply(payload)) {
                return;
            }
            PaintingLibrary after = library.library();
            drafts.onSync(after);
            PaintingLibraryAccess.set(library::library);
            CreativeModeTabsMixin.custompaintings$setCachedParameters(null);
            if (texturesChanged(before, after)) {
                CustomPaintingsMod.LOGGER.info("Synced {} custom painting(s), revision {}; reloading atlases",
                        payload.entries().size(), payload.revision());
                requestReload();
            } else {
                CustomPaintingsMod.LOGGER.info("Synced {} custom painting(s), revision {}; metadata only, no atlas reload",
                        payload.entries().size(), payload.revision());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(PaintingPayloads.Result.TYPE, (payload, context) ->
                imports.complete(payload));
        // Fabric's server JOIN event can run before the client has registered its play channels.
        // Request an authoritative snapshot as soon as the client play listener can send packets.
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) ->
                ClientPlayNetworking.send(new PaintingPayloads.SyncRequest(PaintingPayloads.SyncRequest.PROTOCOL_VERSION)));
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            drafts.discard();
            library.clear();
            PaintingLibraryAccess.set(library::library);
            CreativeModeTabsMixin.custompaintings$setCachedParameters(null);
        });
    }

    /** Name/recipe/permission changes do not alter atlas pixels; only image/canvas/fit changes do. */
    private static boolean texturesChanged(PaintingLibrary before, PaintingLibrary after) {
        if (before.count() != after.count()) {
            return true;
        }
        for (PaintingEntry entry : after.entries()) {
            PaintingEntry previous = before.get(entry.slot()).orElse(null);
            if (previous == null || !previous.sha256().equals(entry.sha256())
                    || previous.width() != entry.width() || previous.height() != entry.height()
                    || previous.fit() != entry.fit()) {
                return true;
            }
        }
        return false;
    }

    /** Coalesce overlapping syncs into at most one running reload plus one follow-up reload. */
    private static void requestReload() {
        Minecraft minecraft = Minecraft.getInstance();
        if (reloadRunning) {
            reloadPending = true;
            return;
        }
        reloadRunning = true;
        minecraft.reloadResourcePacks().whenCompleteAsync((ignored, error) -> {
            reloadRunning = false;
            if (error != null) {
                CustomPaintingsMod.LOGGER.error("Custom painting atlas reload failed", error);
            }
            if (reloadPending) {
                reloadPending = false;
                requestReload();
            }
        }, minecraft);
    }
}
