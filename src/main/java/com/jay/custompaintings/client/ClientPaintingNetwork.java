package com.jay.custompaintings.client;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.client.mixin.CreativeModeTabsMixin;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;
import com.jay.custompaintings.library.PaintingLibraryAccess;
import com.jay.custompaintings.network.PaintingPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashSet;
import java.util.Set;

/**
 * Applies authoritative metadata pages and streams artwork on demand.
 *
 * <p>Because paintings now use standalone dynamic textures rather than an atlas, syncing or
 * editing a painting never triggers a client resource reload — the affected textures are
 * simply invalidated and recomposed. That keeps large libraries responsive.</p>
 */
public final class ClientPaintingNetwork {
    private ClientPaintingNetwork() {
    }

    public static void register(ClientPaintingLibrary library, PaintingImportService imports, PaintingDrafts drafts) {
        ClientPlayNetworking.registerGlobalReceiver(PaintingPayloads.SyncPage.TYPE, (payload, context) -> {
            PaintingLibrary before = library.library();
            if (!library.applyPage(payload)) {
                return;
            }
            PaintingLibrary after = library.library();
            drafts.onSync(after);
            PaintingLibraryAccess.set(library::library);
            CreativeModeTabsMixin.custompaintings$setCachedParameters(null);
            invalidateChanged(before, after);
            CustomPaintingsMod.LOGGER.info("Synced {} custom painting(s), revision {}", after.count(), after.revision());
        });
        ClientPlayNetworking.registerGlobalReceiver(PaintingPayloads.ImageStart.TYPE, (payload, context) ->
                library.beginImage(payload));
        ClientPlayNetworking.registerGlobalReceiver(PaintingPayloads.ImageChunk.TYPE, (payload, context) ->
                library.acceptChunk(payload).ifPresent(id ->
                        // Pixels arrived; drop any stale texture so the next frame recomposes it.
                        CustomPaintingsClient.paintings().releaseTextures(id)));
        ClientPlayNetworking.registerGlobalReceiver(PaintingPayloads.Result.TYPE, (payload, context) ->
                imports.complete(payload));
        // Fabric's server JOIN event can run before the client registers its play channels.
        ClientPlayConnectionEvents.JOIN.register((listener, sender, client) ->
                ClientPlayNetworking.send(new PaintingPayloads.SyncRequest(PaintingPayloads.SyncRequest.PROTOCOL_VERSION)));
        ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
            imports.disconnect();
            drafts.discard();
            CustomPaintingsClient.paintings().close();
            library.clear();
            PaintingLibraryAccess.set(library::library);
            CreativeModeTabsMixin.custompaintings$setCachedParameters(null);
        });
    }

    /**
     * Releases textures whose pixels or canvas changed. {@code invalidate} also clears the
     * cached bytes, so a replaced image is re-fetched instead of redrawn from stale data.
     */
    private static void invalidateChanged(PaintingLibrary before, PaintingLibrary after) {
        PaintingTextureManager textures = CustomPaintingsClient.paintings();
        if (textures == null) {
            return;
        }
        Set<Integer> live = new HashSet<>();
        for (PaintingEntry entry : after.entries()) {
            live.add(entry.id());
            PaintingEntry previous = before.get(entry.id()).orElse(null);
            if (previous == null) {
                continue;
            }
            if (!previous.sha256().equals(entry.sha256())) {
                // New pixels: drop the cached bytes too so they are re-fetched.
                textures.invalidate(entry.id());
            } else if (previous.width() != entry.width() || previous.height() != entry.height()
                    || previous.fit() != entry.fit()) {
                // Same master image, new canvas: recompose locally, no re-download.
                textures.releaseTextures(entry.id());
            }
        }
        for (PaintingEntry entry : before.entries()) {
            if (!live.contains(entry.id())) {
                textures.invalidate(entry.id());
            }
        }
    }
}
