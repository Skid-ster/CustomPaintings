package com.jay.custompaintings.server;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.image.ImageFit;
import com.jay.custompaintings.item.CustomPaintingItems;
import com.jay.custompaintings.library.PaintingData;
import com.jay.custompaintings.library.PaintingEdit;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;
import com.jay.custompaintings.library.PaintingLibraryStore;
import com.jay.custompaintings.library.PaintingRuntime;
import com.jay.custompaintings.network.PaintingPayloads;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Authoritative mutations, permission checks, chunked image transfer and paged metadata sync.
 *
 * <p>Uploads are staged per player and committed as one atomic library mutation. Images are
 * served lazily per id, so joining a world with thousands of paintings transfers only small
 * metadata pages until artwork is actually looked at.</p>
 */
public final class PaintingServerNetwork {
    /** Drop abandoned staged uploads so a disconnected client cannot leak server memory. */
    private static final long STAGING_TIMEOUT_MS = 120_000L;
    private static final int MAX_STAGED_PER_PLAYER = PaintingPayloads.MAX_BATCH_IMAGES;
    private static final long MAX_STAGED_BYTES_PER_PLAYER = 64L * 1024 * 1024;

    private static MinecraftServer activeServer;
    private static boolean mutationBusy;
    private static final Map<UUID, Map<Long, Staged>> STAGING = new HashMap<>();

    private PaintingServerNetwork() {
    }

    private static final class Staged {
        private final PaintingPayloads.UploadStart start;
        private final PaintingPayloads.Assembler assembler;
        private final long createdAt = System.currentTimeMillis();

        private Staged(PaintingPayloads.UploadStart start) {
            this.start = start;
            this.assembler = new PaintingPayloads.Assembler(start.sha256(), start.totalBytes());
        }
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(PaintingPayloads.SyncRequest.TYPE, (payload, context) -> {
            if (payload.protocolVersion() != PaintingPayloads.SyncRequest.PROTOCOL_VERSION) {
                CustomPaintingsMod.LOGGER.warn("Ignoring painting sync request with protocol version {}",
                        payload.protocolVersion());
                return;
            }
            sendSync(context.player());
        });
        ServerPlayNetworking.registerGlobalReceiver(PaintingPayloads.ImageRequest.TYPE, (payload, context) ->
                sendImage(context.player(), payload.id()));
        ServerPlayNetworking.registerGlobalReceiver(PaintingPayloads.UploadStart.TYPE, (payload, context) ->
                stageUpload(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(PaintingPayloads.ImageChunk.TYPE, (payload, context) ->
                acceptChunk(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(PaintingPayloads.UploadCommit.TYPE, (payload, context) ->
                commitUploads(context.server(), context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(PaintingPayloads.ApplyBatch.TYPE, (payload, context) ->
                mutate(context.server(), context.player(), payload.requestId(), () -> {
                    List<PaintingEdit> edits = payload.edits().stream().map(PaintingPayloads.EditData::edit).toList();
                    PaintingServerState.store().applyBatch(payload.expectedRevision(), edits, payload.removals());
                    return (edits.size() + payload.removals().size()) + " staged change(s) applied";
                }, () -> {
                    for (PaintingPayloads.EditData edit : payload.edits()) {
                        updatePlacedPaintings(context.server(), edit.id(), edit.name(), edit.width(), edit.height());
                    }
                    for (int id : payload.removals()) {
                        removePlacedPaintings(context.server(), id);
                    }
                }, !payload.removals().isEmpty()));
        ServerPlayNetworking.registerGlobalReceiver(PaintingPayloads.GiveRequest.TYPE, (payload, context) ->
                giveFromMenu(context.player(), payload));

        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) -> sendSync(listener.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((listener, server) -> {
            synchronized (STAGING) {
                STAGING.remove(listener.getPlayer().getUUID());
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            activeServer = server;
            com.jay.custompaintings.library.PaintingLibraryAccess.set(PaintingServerState::library);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (activeServer == server) {
                activeServer = null;
            }
            mutationBusy = false;
            synchronized (STAGING) {
                STAGING.clear();
            }
        });
    }

    /** Only the owner/operator may change the shared library. */
    public static boolean canEdit(ServerPlayer player) {
        return player.level().getServer().isSingleplayerOwner(player.nameAndId())
                || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /**
     * Taking a painting from the menu is intentionally available to everyone in both Survival
     * and Creative: it is the acquisition path once the crafting catalogue is exhausted.
     */
    public static boolean canUseMenu(ServerPlayer player) {
        return PaintingServerState.config().menuAllowedForEveryone() || canEdit(player);
    }

    private static void giveFromMenu(ServerPlayer player, PaintingPayloads.GiveRequest payload) {
        if (!canUseMenu(player)) {
            ServerPlayNetworking.send(player, new PaintingPayloads.Result(payload.requestId(), false,
                    "The Painting Menu is restricted to operators on this server"));
            return;
        }
        PaintingEntry entry = PaintingServerState.library().get(payload.id()).orElse(null);
        if (entry == null) {
            ServerPlayNetworking.send(player, new PaintingPayloads.Result(payload.requestId(), false,
                    "That painting no longer exists"));
            return;
        }
        // Clamp to the item's real stack size so the menu never promises more than it delivers.
        int count = Math.max(1, Math.min(CustomPaintingItems.get().getDefaultMaxStackSize(), payload.count()));
        ItemStack stack = CustomPaintingItems.stack(entry.id(), count);
        // Prefer an empty hotbar slot so the painting is immediately usable.
        var inventory = player.getInventory();
        int hotbarSlot = -1;
        for (int slot = 0; slot < 9; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                hotbarSlot = slot;
                break;
            }
        }
        boolean added;
        if (hotbarSlot >= 0) {
            inventory.setItem(hotbarSlot, stack);
            added = true;
        } else {
            added = inventory.add(stack);
        }
        if (!added) {
            ServerPlayNetworking.send(player, new PaintingPayloads.Result(payload.requestId(), false,
                    "Your inventory is full"));
            return;
        }
        inventory.setChanged();
        player.inventoryMenu.broadcastChanges();
        ServerPlayNetworking.send(player, new PaintingPayloads.Result(payload.requestId(), true,
                "Added " + entry.name()));
    }

    private static void stageUpload(ServerPlayer player, PaintingPayloads.UploadStart payload) {
        if (!canEdit(player)) {
            return;
        }
        if (payload.totalBytes() <= 0 || payload.totalBytes() > PaintingPayloads.MAX_IMAGE_BYTES) {
            return;
        }
        synchronized (STAGING) {
            pruneStaging();
            Map<Long, Staged> staged = STAGING.computeIfAbsent(player.getUUID(), key -> new HashMap<>());
            if (staged.containsKey(payload.transferId())) {
                return;
            }
            long bytes = staged.values().stream().mapToLong(value -> value.assembler.totalBytes()).sum();
            if (staged.size() >= MAX_STAGED_PER_PLAYER
                    || bytes + payload.totalBytes() > MAX_STAGED_BYTES_PER_PLAYER) {
                CustomPaintingsMod.LOGGER.warn("Rejecting staged upload from {}: staging limit reached",
                        player.getGameProfile().name());
                return;
            }
            try {
                staged.put(payload.transferId(), new Staged(payload));
            } catch (RuntimeException exception) {
                CustomPaintingsMod.LOGGER.warn("Rejected invalid upload start", exception);
            }
        }
    }

    private static void acceptChunk(ServerPlayer player, PaintingPayloads.ImageChunk payload) {
        synchronized (STAGING) {
            Map<Long, Staged> staged = STAGING.get(player.getUUID());
            if (staged == null) {
                return;
            }
            Staged value = staged.get(payload.transferId());
            if (value != null) {
                value.assembler.accept(payload.index(), payload.data());
            }
        }
    }

    private static void pruneStaging() {
        long now = System.currentTimeMillis();
        STAGING.values().forEach(map -> map.values().removeIf(value -> now - value.createdAt > STAGING_TIMEOUT_MS));
        STAGING.values().removeIf(Map::isEmpty);
    }

    private static void commitUploads(MinecraftServer server, ServerPlayer player,
                                      PaintingPayloads.UploadCommit payload) {
        List<PaintingLibraryStore.Upload> uploads = new ArrayList<>();
        synchronized (STAGING) {
            Map<Long, Staged> staged = STAGING.get(player.getUUID());
            if (staged == null || payload.transferIds().isEmpty()) {
                ServerPlayNetworking.send(player, new PaintingPayloads.Result(payload.requestId(), false,
                        "The upload expired; please try again"));
                return;
            }
            Set<Long> seen = new HashSet<>();
            for (long transferId : payload.transferIds()) {
                if (!seen.add(transferId)) {
                    ServerPlayNetworking.send(player, new PaintingPayloads.Result(payload.requestId(), false,
                            "Duplicate image in upload"));
                    return;
                }
                Staged value = staged.get(transferId);
                if (value == null || !value.assembler.complete()) {
                    ServerPlayNetworking.send(player, new PaintingPayloads.Result(payload.requestId(), false,
                            "The upload did not finish; please try again"));
                    return;
                }
                uploads.add(new PaintingLibraryStore.Upload(value.start.name(), value.start.width(),
                        value.start.height(), ImageFit.fromId(value.start.fit()), value.assembler.finish()));
            }
        }
        mutate(server, player, payload.requestId(), () -> {
            int added = PaintingServerState.store().addBatch(uploads).size();
            return added + " painting(s) added";
        }, () -> {
            synchronized (STAGING) {
                Map<Long, Staged> staged = STAGING.get(player.getUUID());
                if (staged != null) {
                    payload.transferIds().forEach(staged::remove);
                }
            }
        });
    }

    private interface Mutation {
        String run() throws IOException;
    }

    private static void mutate(MinecraftServer server, ServerPlayer player, long requestId, Mutation mutation) {
        mutate(server, player, requestId, mutation, () -> { }, true);
    }

    private static void mutate(MinecraftServer server, ServerPlayer player, long requestId,
                               Mutation mutation, Runnable afterSuccess, boolean reloadData) {
        if (!canEdit(player)) {
            ServerPlayNetworking.send(player, new PaintingPayloads.Result(requestId, false,
                    "Only the singleplayer owner or a server operator can edit paintings"));
            return;
        }
        if (mutationBusy) {
            ServerPlayNetworking.send(player, new PaintingPayloads.Result(requestId, false,
                    "Another painting operation is still applying"));
            return;
        }
        mutationBusy = true;
        CompletableFuture.supplyAsync(() -> {
            try {
                return mutation.run();
            } catch (IOException | RuntimeException exception) {
                CustomPaintingsMod.LOGGER.warn("Painting mutation failed", exception);
                return "ERROR:" + message(exception);
            }
        }, net.minecraft.util.Util.ioPool()).thenComposeAsync(result -> {
            if (result.startsWith("ERROR:")) {
                return CompletableFuture.completedFuture(result);
            }
            try {
                afterSuccess.run();
            } catch (RuntimeException exception) {
                CustomPaintingsMod.LOGGER.error("Painting saved but updating placed entities failed", exception);
                return CompletableFuture.completedFuture(
                        "ERROR:Painting saved, but updating placed paintings failed; rejoin the world");
            }
            if (!reloadData) {
                // Metadata-only batches keep recipe occupancy, and recipes read the live library.
                return CompletableFuture.completedFuture(result);
            }
            // Recipe occupancy changed: refresh the recipe book so new/removed crafts appear.
            return server.reloadResources(server.getPackRepository().getSelectedIds())
                    .thenApply(ignored -> result)
                    .exceptionally(exception -> {
                        CustomPaintingsMod.LOGGER.error("Painting data reload failed", exception);
                        return "ERROR:Painting saved, but the data reload failed; rejoin the world";
                    });
        }, server).whenCompleteAsync((result, failure) -> {
            mutationBusy = false;
            String message = result;
            if (failure != null) {
                CustomPaintingsMod.LOGGER.error("Painting mutation pipeline failed", failure);
                message = "ERROR:Painting operation failed; check the server log";
            }
            boolean success = !message.startsWith("ERROR:");
            try {
                broadcastSync(server);
            } catch (RuntimeException exception) {
                CustomPaintingsMod.LOGGER.error("Could not broadcast painting sync", exception);
            }
            ServerPlayNetworking.send(player, new PaintingPayloads.Result(requestId, success,
                    success ? message : message.substring("ERROR:".length())));
        }, server);
    }

    private static String message(Throwable exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    /** Snapshot first: discarding/dropping mutates the live entity lookup while we iterate. */
    private static List<Painting> paintingsWithId(ServerLevel level, int id) {
        List<Painting> paintings = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (entity instanceof Painting painting
                    && PaintingData.entityPaintingId(painting).stream().anyMatch(value -> value == id)) {
                paintings.add(painting);
            }
        }
        return paintings;
    }

    private static void removePlacedPaintings(MinecraftServer server, int id) {
        server.getAllLevels().forEach(level -> paintingsWithId(level, id).forEach(Entity::discard));
    }

    private static void updatePlacedPaintings(MinecraftServer server, int id, String name, int width, int height) {
        String normalized = PaintingEntry.normalizeName(name);
        server.getAllLevels().forEach(level -> {
            var holder = level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT)
                    .get(PaintingRuntime.variantKey(width, height)).orElse(null);
            for (Painting painting : paintingsWithId(level, id)) {
                painting.setCustomName(Component.literal(normalized));
                if (holder == null) {
                    continue;
                }
                var previous = painting.getVariant();
                if (previous.is(holder.unwrapKey().orElseThrow())) {
                    continue;
                }
                ((com.jay.custompaintings.mixin.PaintingAccessor) painting).custompaintings$setVariant(holder);
                if (!painting.survives()) {
                    // The new size no longer fits this wall: return the item instead of vanishing.
                    ((com.jay.custompaintings.mixin.PaintingAccessor) painting).custompaintings$setVariant(previous);
                    painting.dropItem(level, null);
                    painting.discard();
                }
            }
        });
    }

    public static void broadcastSync(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendSync(player);
        }
    }

    /** Metadata only, in bounded pages. Artwork follows lazily via {@link #sendImage}. */
    private static void sendSync(ServerPlayer player) {
        if (!ServerPlayNetworking.canSend(player, PaintingPayloads.SyncPage.TYPE)) {
            return;
        }
        PaintingLibrary library = PaintingServerState.library();
        List<PaintingPayloads.EntryData> all = PaintingPayloads.metadata(library);
        boolean canEdit = canEdit(player);
        int pageCount = Math.max(1, (all.size() + PaintingPayloads.ENTRIES_PER_PAGE - 1)
                / PaintingPayloads.ENTRIES_PER_PAGE);
        for (int page = 0; page < pageCount; page++) {
            int from = page * PaintingPayloads.ENTRIES_PER_PAGE;
            int to = Math.min(all.size(), from + PaintingPayloads.ENTRIES_PER_PAGE);
            ServerPlayNetworking.send(player, new PaintingPayloads.SyncPage(library.revision(),
                    library.recipeSeed(), canEdit, page, pageCount, all.size(), all.subList(from, to)));
        }
    }

    private static void sendImage(ServerPlayer player, int id) {
        if (!ServerPlayNetworking.canSend(player, PaintingPayloads.ImageStart.TYPE)) {
            return;
        }
        PaintingEntry entry = PaintingServerState.library().get(id).orElse(null);
        if (entry == null) {
            ServerPlayNetworking.send(player, new PaintingPayloads.ImageStart(id, "", 0));
            return;
        }
        byte[] png;
        try {
            png = Files.readAllBytes(PaintingServerState.store().imagePath(entry));
        } catch (IOException exception) {
            CustomPaintingsMod.LOGGER.error("Could not read master image for painting {}", id, exception);
            ServerPlayNetworking.send(player, new PaintingPayloads.ImageStart(id, "", 0));
            return;
        }
        ServerPlayNetworking.send(player, new PaintingPayloads.ImageStart(id, entry.sha256(), png.length));
        for (int index = 0; index < PaintingPayloads.chunkCount(png.length); index++) {
            ServerPlayNetworking.send(player, new PaintingPayloads.ImageChunk(id, index,
                    PaintingPayloads.chunk(png, index)));
        }
    }
}
