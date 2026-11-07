package com.jay.custompaintings.network;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.image.ImageFit;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/** Bounded custom payloads. Images are aspect-preserving master PNGs, never arbitrary original files. */
public final class PaintingPayloads {
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_SYNC_BYTES = 1 + PaintingEntry.MAX_PAINTINGS * (MAX_IMAGE_BYTES + 1024);

    public record EntryData(int slot, String name, int width, int height, String fit,
                            String sha256, String recipeId, byte[] png) {
        public static final StreamCodec<RegistryFriendlyByteBuf, EntryData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, EntryData::slot,
                ByteBufCodecs.STRING_UTF8, EntryData::name,
                ByteBufCodecs.VAR_INT, EntryData::width,
                ByteBufCodecs.VAR_INT, EntryData::height,
                ByteBufCodecs.STRING_UTF8, EntryData::fit,
                ByteBufCodecs.STRING_UTF8, EntryData::sha256,
                ByteBufCodecs.STRING_UTF8, EntryData::recipeId,
                ByteBufCodecs.byteArray(MAX_IMAGE_BYTES), EntryData::png,
                EntryData::new);

        public PaintingEntry entry() {
            return new PaintingEntry(slot, name, width, height, ImageFit.fromId(fit),
                    "slot_" + slot + ".png", sha256, recipeId);
        }
    }

    public record Sync(long revision, long recipeSeed, boolean canEdit, List<EntryData> entries) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(CustomPaintingsMod.id("library_sync_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Sync::revision,
                ByteBufCodecs.VAR_LONG, Sync::recipeSeed,
                ByteBufCodecs.BOOL, Sync::canEdit,
                EntryData.STREAM_CODEC.apply(ByteBufCodecs.list(PaintingEntry.MAX_PAINTINGS)), Sync::entries,
                Sync::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public PaintingLibrary library() {
            return new PaintingLibrary(revision, recipeSeed, entries.stream().map(EntryData::entry).toList());
        }
    }

    public record Upload(long requestId, String name, int width, int height, String fit, byte[] png) implements CustomPacketPayload {
        public static final Type<Upload> TYPE = new Type<>(CustomPaintingsMod.id("upload_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Upload> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Upload::requestId,
                ByteBufCodecs.STRING_UTF8, Upload::name,
                ByteBufCodecs.VAR_INT, Upload::width,
                ByteBufCodecs.VAR_INT, Upload::height,
                ByteBufCodecs.STRING_UTF8, Upload::fit,
                ByteBufCodecs.byteArray(MAX_IMAGE_BYTES), Upload::png,
                Upload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Rename(long requestId, int slot, String name) implements CustomPacketPayload {
        public static final Type<Rename> TYPE = new Type<>(CustomPaintingsMod.id("rename"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Rename> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Rename::requestId, ByteBufCodecs.VAR_INT, Rename::slot,
                ByteBufCodecs.STRING_UTF8, Rename::name, Rename::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Atomic name/size/fit edit. Replaces the old lossy resize payload. */
    public record Adjust(long requestId, int slot, String name, int width, int height, String fit) implements CustomPacketPayload {
        public static final Type<Adjust> TYPE = new Type<>(CustomPaintingsMod.id("adjust_v2"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Adjust> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Adjust::requestId, ByteBufCodecs.VAR_INT, Adjust::slot,
                ByteBufCodecs.STRING_UTF8, Adjust::name,
                ByteBufCodecs.VAR_INT, Adjust::width, ByteBufCodecs.VAR_INT, Adjust::height,
                ByteBufCodecs.STRING_UTF8, Adjust::fit, Adjust::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Remove(long requestId, int slot) implements CustomPacketPayload {
        public static final Type<Remove> TYPE = new Type<>(CustomPaintingsMod.id("remove"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Remove> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Remove::requestId, ByteBufCodecs.VAR_INT, Remove::slot, Remove::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Sent by the client once its play listener is ready, so login cannot race channel registration. */
    public record SyncRequest(int protocolVersion) implements CustomPacketPayload {
        public static final int PROTOCOL_VERSION = 2;
        public static final Type<SyncRequest> TYPE = new Type<>(CustomPaintingsMod.id("request_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncRequest> STREAM_CODEC =
                StreamCodec.of((buf, request) -> buf.writeVarInt(request.protocolVersion()),
                        buf -> new SyncRequest(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Result(long requestId, boolean success, String message) implements CustomPacketPayload {
        public static final Type<Result> TYPE = new Type<>(CustomPaintingsMod.id("operation_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Result> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Result::requestId, ByteBufCodecs.BOOL, Result::success,
                ByteBufCodecs.STRING_UTF8, Result::message, Result::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private PaintingPayloads() {
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().registerLarge(Sync.TYPE, Sync.STREAM_CODEC, MAX_SYNC_BYTES);
        PayloadTypeRegistry.clientboundPlay().register(Result.TYPE, Result.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().registerLarge(Upload.TYPE, Upload.STREAM_CODEC, MAX_IMAGE_BYTES + 1024);
        PayloadTypeRegistry.serverboundPlay().register(Rename.TYPE, Rename.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Adjust.TYPE, Adjust.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Remove.TYPE, Remove.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SyncRequest.TYPE, SyncRequest.STREAM_CODEC);
    }
}
