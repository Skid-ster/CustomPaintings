package com.jay.custompaintings.server;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.image.ImageFit;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;
import com.jay.custompaintings.library.PaintingRuntime;
import com.jay.custompaintings.network.PaintingPayloads;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ClientboundPlayChannelEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Authoritative mutations, permission checks, data reloads and image synchronization. */
public final class PaintingServerNetwork {
    private static MinecraftServer activeServer;
    private static boolean mutationBusy;

    private PaintingServerNetwork() {
    }

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(PaintingPayloads.Upload.TYPE, (payload, context) ->
                mutate(context.server(), context.player(), payload.requestId(), () -> {
                    PaintingServerState.store().add(payload.name(), payload.width(), payload.height(), ImageFit.fromId(payload.fit()), payload.png());
                    return "Painting added";
                }));
        ServerPlayNetworking.registerGlobalReceiver(PaintingPayloads.Rename.TYPE, (payload, context) ->
                mutate(context.server(), context.player(), payload.requestId(), () -> {
                    PaintingServerState.store().rename(payload.slot(), payload.name());
                    return "Painting renamed";
                }, () -> renamePlacedPaintings(context.server(), payload.slot(), payload.name())));
        ServerPlayNetworking.registerGlobalReceiver(PaintingPayloads.Adjust.TYPE, (payload, context) ->
                mutate(context.server(), context.player(), payload.requestId(), () -> {
                    PaintingServerState.store().adjust(payload.slot(), payload.name(), payload.width(), payload.height(),
                            ImageFit.fromId(payload.fit()));
                    return "Painting updated";
                }, () -> {
                    renamePlacedPaintings(context.server(), payload.slot(), payload.name());
                    resizePlacedPaintings(context.server(), payload.slot(), payload.width(), payload.height());
                }));
        ServerPlayNetworking.registerGlobalReceiver(PaintingPayloads.Remove.TYPE, (payload, context) ->
                mutate(context.server(), context.player(), payload.requestId(), () -> {
                    PaintingServerState.store().remove(payload.slot());
                    return "Painting removed";
                }, () -> removePlacedPaintings(context.server(), payload.slot())));
        ServerPlayNetworking.registerGlobalReceiver(PaintingPayloads.SyncRequest.TYPE, (payload, context) -> {
            if (payload.protocolVersion() != PaintingPayloads.SyncRequest.PROTOCOL_VERSION) {
                CustomPaintingsMod.LOGGER.warn("Ignoring painting sync request with protocol version {}", payload.protocolVersion());
                return;
            }
            // The client explicitly asked for a snapshot, so it has the mod and a ready play receiver.
            sendSync(context.player(), context.server(), false);
        });
        // Fabric fires JOIN before the client's minecraft:register packet arrives. Also sync when the
        // client actually advertises the sync channel; duplicate snapshots are deduplicated client-side.
        ClientboundPlayChannelEvents.REGISTER.register((listener, sender, server, channels) -> {
            if (channels.contains(PaintingPayloads.Sync.TYPE.id())) {
                sendSync(listener.getPlayer(), server, false);
            }
        });
        ServerPlayConnectionEvents.JOIN.register((listener, sender, server) ->
                sendSync(listener.getPlayer(), server, true));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            activeServer = server;
            com.jay.custompaintings.library.PaintingLibraryAccess.set(PaintingServerState::library);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            if (activeServer == server) {
                activeServer = null;
            }
            mutationBusy = false;
        });
    }

    public static boolean canEdit(ServerPlayer player) {
        return player.level().getServer().isSingleplayerOwner(player.nameAndId())
                || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private interface Mutation {
        String run() throws IOException;
    }

    private static void mutate(MinecraftServer server, ServerPlayer player, long requestId, Mutation mutation) {
        mutate(server, player, requestId, mutation, () -> { });
    }

    private static void mutate(MinecraftServer server, ServerPlayer player, long requestId,
                               Mutation mutation, Runnable afterSuccess) {
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
                return "ERROR:" + (exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            }
        }, net.minecraft.util.Util.ioPool()).thenComposeAsync(message -> {
            if (message.startsWith("ERROR:")) {
                return CompletableFuture.completedFuture(message);
            }
            afterSuccess.run();
            return server.reloadResources(server.getPackRepository().getSelectedIds())
                    .thenApply(ignored -> message)
                    .exceptionally(exception -> {
                        CustomPaintingsMod.LOGGER.error("Painting data reload failed", exception);
                        return "ERROR:Painting saved, but the data reload failed; rejoin the world";
                    });
        }, server).thenAcceptAsync(message -> {
            mutationBusy = false;
            boolean success = !message.startsWith("ERROR:");
            broadcastSync(server);
            ServerPlayNetworking.send(player, new PaintingPayloads.Result(requestId, success,
                    success ? message : message.substring("ERROR:".length())));
        }, server);
    }

    private static void removePlacedPaintings(MinecraftServer server, int slot) {
        server.getAllLevels().forEach(level -> {
            // Snapshot first: discard()/dropItem() mutate the live entity lookup while we iterate.
            List<net.minecraft.world.entity.decoration.painting.Painting> paintings = new ArrayList<>();
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof net.minecraft.world.entity.decoration.painting.Painting painting
                        && painting.getVariant().unwrapKey()
                        .flatMap(key -> PaintingRuntime.slotFromVariant(key.identifier()))
                        .map(id -> id == slot).orElse(false)) {
                    paintings.add(painting);
                }
            }
            paintings.forEach(net.minecraft.world.entity.Entity::discard);
        });
    }

    private static void resizePlacedPaintings(MinecraftServer server, int slot, int width, int height) {
        server.getAllLevels().forEach(level -> {
            var holder = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.PAINTING_VARIANT)
                    .get(PaintingRuntime.variantKey(slot, width, height)).orElse(null);
            if (holder == null) {
                return;
            }
            // Snapshot first: setVariant()/dropItem()/discard() can mutate the live entity lookup.
            List<net.minecraft.world.entity.decoration.painting.Painting> paintings = new ArrayList<>();
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof net.minecraft.world.entity.decoration.painting.Painting painting
                        && painting.getVariant().unwrapKey()
                        .flatMap(key -> PaintingRuntime.slotFromVariant(key.identifier()))
                        .map(id -> id == slot).orElse(false)) {
                    paintings.add(painting);
                }
            }
            for (var painting : paintings) {
                var previous = painting.getVariant();
                ((com.jay.custompaintings.mixin.PaintingAccessor) painting).custompaintings$setVariant(holder);
                if (!painting.survives()) {
                    // The new size no longer fits on this wall. Return the item instead of leaving a missing texture.
                    ((com.jay.custompaintings.mixin.PaintingAccessor) painting).custompaintings$setVariant(previous);
                    painting.dropItem(level, null);
                    painting.discard();
                }
            }
        });
    }

    private static void renamePlacedPaintings(MinecraftServer server, int slot, String name) {
        String normalized = PaintingEntry.normalizeName(name);
        server.getAllLevels().forEach(level -> {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                if (entity instanceof net.minecraft.world.entity.decoration.painting.Painting painting) {
                    painting.getVariant().unwrapKey().flatMap(key -> PaintingRuntime.slotFromVariant(key.identifier()))
                            .ifPresent(id -> {
                                if (id == slot) {
                                    painting.setCustomName(net.minecraft.network.chat.Component.literal(normalized));
                                }
                            });
                }
            }
        });
    }

    public static void broadcastSync(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendSync(player, server, true);
        }
    }

    private static void sendSync(ServerPlayer player, MinecraftServer server, boolean requireChannel) {
        if (requireChannel && !ServerPlayNetworking.canSend(player, PaintingPayloads.Sync.TYPE)) {
            return;
        }
        PaintingLibrary library = PaintingServerState.library();
        List<PaintingPayloads.EntryData> entries = new ArrayList<>();
        try {
            for (PaintingEntry entry : library.entries()) {
                byte[] png = Files.readAllBytes(PaintingServerState.store().imagePath(entry));
                entries.add(new PaintingPayloads.EntryData(entry.slot(), entry.name(), entry.width(), entry.height(),
                        entry.fit().id(), entry.sha256(), entry.recipeId(), png));
            }
        } catch (IOException exception) {
            CustomPaintingsMod.LOGGER.error("Could not read painting images for sync", exception);
            return;
        }
        ServerPlayNetworking.send(player, new PaintingPayloads.Sync(library.revision(), library.recipeSeed(),
                canEdit(player), entries));
    }
}
