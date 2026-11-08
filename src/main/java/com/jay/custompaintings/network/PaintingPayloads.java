package com.jay.custompaintings.network;

import com.jay.custompaintings.CustomPaintingsMod;
import com.jay.custompaintings.image.ImageFit;
import com.jay.custompaintings.library.PaintingEdit;
import com.jay.custompaintings.library.PaintingEntry;
import com.jay.custompaintings.library.PaintingLibrary;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/**
 * Protocol v4: built for an unbounded library.
 *
 * <p>Metadata and pixels are separated. A sync sends only small metadata pages, and image
 * bytes move lazily in {@value #CHUNK_BYTES}-byte chunks in either direction, so neither a
 * huge library nor a large master PNG can exceed Minecraft's 8 MiB packet ceiling.</p>
 */
public final class PaintingPayloads {
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    /** Comfortably below every vanilla payload/frame ceiling in both directions. */
    public static final int CHUNK_BYTES = 256 * 1024;
    public static final int MAX_CHUNKS = (MAX_IMAGE_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;
    public static final int ENTRIES_PER_PAGE = 128;
    public static final int MAX_BATCH_IMAGES = 64;
    public static final int MAX_BATCH_CHANGES = 256;
    public static final int MAX_SYNC_PAGE_BYTES = ENTRIES_PER_PAGE * 512;
    public static final int MAX_APPLY_BATCH_BYTES = MAX_BATCH_CHANGES * 512;

    private PaintingPayloads() {
    }

    /** One painting's metadata. Pixels are fetched separately and only when needed. */
    public record EntryData(int id, String name, int width, int height, String fit,
                            String sha256, String recipeId) {
        public static final StreamCodec<RegistryFriendlyByteBuf, EntryData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, EntryData::id,
                ByteBufCodecs.STRING_UTF8, EntryData::name,
                ByteBufCodecs.VAR_INT, EntryData::width,
                ByteBufCodecs.VAR_INT, EntryData::height,
                ByteBufCodecs.STRING_UTF8, EntryData::fit,
                ByteBufCodecs.STRING_UTF8, EntryData::sha256,
                ByteBufCodecs.STRING_UTF8, EntryData::recipeId,
                EntryData::new);

        public PaintingEntry entry() {
            return new PaintingEntry(id, name, width, height, ImageFit.fromId(fit),
                    PaintingEntry.imageFileFor(id), sha256, recipeId);
        }

        public static EntryData from(PaintingEntry entry) {
            return new EntryData(entry.id(), entry.name(), entry.width(), entry.height(),
                    entry.fit().id(), entry.sha256(), entry.recipeId());
        }
    }

    /**
     * One page of the authoritative library. The client applies a revision only once every
     * page has arrived, so a large library never appears half-synced.
     */
    public record SyncPage(long revision, long recipeSeed, int nextId, boolean canEdit, int page, int pageCount,
                           int total, List<EntryData> entries) implements CustomPacketPayload {
        public static final Type<SyncPage> TYPE = new Type<>(CustomPaintingsMod.id("library_page_v5"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncPage> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, SyncPage::revision,
                ByteBufCodecs.VAR_LONG, SyncPage::recipeSeed,
                ByteBufCodecs.VAR_INT, SyncPage::nextId,
                ByteBufCodecs.BOOL, SyncPage::canEdit,
                ByteBufCodecs.VAR_INT, SyncPage::page,
                ByteBufCodecs.VAR_INT, SyncPage::pageCount,
                ByteBufCodecs.VAR_INT, SyncPage::total,
                EntryData.STREAM_CODEC.apply(ByteBufCodecs.list(ENTRIES_PER_PAGE)), SyncPage::entries,
                SyncPage::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Sent by the client once its play listener is ready, so login cannot race channel registration. */
    public record SyncRequest(int protocolVersion) implements CustomPacketPayload {
        public static final int PROTOCOL_VERSION = 5;
        public static final Type<SyncRequest> TYPE = new Type<>(CustomPaintingsMod.id("request_sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncRequest> STREAM_CODEC =
                StreamCodec.of((buf, request) -> buf.writeVarInt(request.protocolVersion()),
                        buf -> new SyncRequest(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client asks for one painting's master PNG (rendering, preview or editor). */
    public record ImageRequest(int id) implements CustomPacketPayload {
        public static final Type<ImageRequest> TYPE = new Type<>(CustomPaintingsMod.id("image_request_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ImageRequest> STREAM_CODEC =
                StreamCodec.of((buf, request) -> buf.writeVarInt(request.id()), buf -> new ImageRequest(buf.readVarInt()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server announces an image transfer; {@code totalBytes == 0} means "no such painting". */
    public record ImageStart(int id, String sha256, int totalBytes) implements CustomPacketPayload {
        public static final Type<ImageStart> TYPE = new Type<>(CustomPaintingsMod.id("image_start_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ImageStart> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ImageStart::id,
                ByteBufCodecs.STRING_UTF8, ImageStart::sha256,
                ByteBufCodecs.VAR_INT, ImageStart::totalBytes,
                ImageStart::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One slice of a master PNG. Used for both downloads and uploads. */
    public record ImageChunk(long transferId, int index, byte[] data) implements CustomPacketPayload {
        public static final Type<ImageChunk> TYPE = new Type<>(CustomPaintingsMod.id("image_chunk_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ImageChunk> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, ImageChunk::transferId,
                ByteBufCodecs.VAR_INT, ImageChunk::index,
                ByteBufCodecs.byteArray(CHUNK_BYTES), ImageChunk::data,
                ImageChunk::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client begins staging one uploaded image on the server. */
    public record UploadStart(long transferId, String name, int width, int height, String fit,
                              String sha256, int totalBytes) implements CustomPacketPayload {
        public static final Type<UploadStart> TYPE = new Type<>(CustomPaintingsMod.id("upload_start_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadStart> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, UploadStart::transferId,
                ByteBufCodecs.STRING_UTF8, UploadStart::name,
                ByteBufCodecs.VAR_INT, UploadStart::width,
                ByteBufCodecs.VAR_INT, UploadStart::height,
                ByteBufCodecs.STRING_UTF8, UploadStart::fit,
                ByteBufCodecs.STRING_UTF8, UploadStart::sha256,
                ByteBufCodecs.VAR_INT, UploadStart::totalBytes,
                UploadStart::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Commit every staged transfer as one atomic library mutation. */
    public record UploadCommit(long requestId, List<Long> transferIds) implements CustomPacketPayload {
        public static final Type<UploadCommit> TYPE = new Type<>(CustomPaintingsMod.id("upload_commit_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, UploadCommit> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, UploadCommit::requestId,
                ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list(MAX_BATCH_IMAGES)), UploadCommit::transferIds,
                UploadCommit::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** One staged metadata edit inside an Apply batch. */
    public record EditData(int id, String name, int width, int height, String fit) {
        public static final StreamCodec<RegistryFriendlyByteBuf, EditData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, EditData::id,
                ByteBufCodecs.STRING_UTF8, EditData::name,
                ByteBufCodecs.VAR_INT, EditData::width,
                ByteBufCodecs.VAR_INT, EditData::height,
                ByteBufCodecs.STRING_UTF8, EditData::fit,
                EditData::new);

        public PaintingEdit edit() {
            return new PaintingEdit(id, name, width, height, ImageFit.fromId(fit));
        }

        public static EditData from(PaintingEdit edit) {
            return new EditData(edit.id(), edit.name(), edit.width(), edit.height(), edit.fit().id());
        }
    }

    /** Apply every staged edit/removal together after one explicit user confirmation. */
    public record ApplyBatch(long requestId, long expectedRevision, List<EditData> edits, List<Integer> removals)
            implements CustomPacketPayload {
        public static final Type<ApplyBatch> TYPE = new Type<>(CustomPaintingsMod.id("apply_batch_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ApplyBatch> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, ApplyBatch::requestId,
                ByteBufCodecs.VAR_LONG, ApplyBatch::expectedRevision,
                EditData.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_BATCH_CHANGES)), ApplyBatch::edits,
                ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_BATCH_CHANGES)), ApplyBatch::removals,
                ApplyBatch::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Painting Menu acquisition. Deliberately allowed in Survival as well as Creative: the
     * menu is the acquisition path once the finite crafting catalogue is exhausted.
     */
    public record GiveRequest(long requestId, int id, int count) implements CustomPacketPayload {
        public static final Type<GiveRequest> TYPE = new Type<>(CustomPaintingsMod.id("give_painting_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, GiveRequest> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, GiveRequest::requestId,
                ByteBufCodecs.VAR_INT, GiveRequest::id,
                ByteBufCodecs.VAR_INT, GiveRequest::count,
                GiveRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Result(long requestId, boolean success, String message) implements CustomPacketPayload {
        public static final Type<Result> TYPE = new Type<>(CustomPaintingsMod.id("result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Result> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_LONG, Result::requestId,
                ByteBufCodecs.BOOL, Result::success,
                ByteBufCodecs.STRING_UTF8, Result::message,
                Result::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().registerLarge(SyncPage.TYPE, SyncPage.STREAM_CODEC, MAX_SYNC_PAGE_BYTES);
        PayloadTypeRegistry.clientboundPlay().register(ImageStart.TYPE, ImageStart.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(ImageChunk.TYPE, ImageChunk.STREAM_CODEC, CHUNK_BYTES + 1024);
        PayloadTypeRegistry.clientboundPlay().register(Result.TYPE, Result.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(SyncRequest.TYPE, SyncRequest.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ImageRequest.TYPE, ImageRequest.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().registerLarge(ImageChunk.TYPE, ImageChunk.STREAM_CODEC, CHUNK_BYTES + 1024);
        PayloadTypeRegistry.serverboundPlay().register(UploadStart.TYPE, UploadStart.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(UploadCommit.TYPE, UploadCommit.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().registerLarge(ApplyBatch.TYPE, ApplyBatch.STREAM_CODEC, MAX_APPLY_BATCH_BYTES);
        PayloadTypeRegistry.serverboundPlay().register(GiveRequest.TYPE, GiveRequest.STREAM_CODEC);
    }

    /** Reassembles a chunked master PNG, tolerating any chunk arrival order. */
    public static final class Assembler {
        private final byte[] bytes;
        private final boolean[] received;
        private final String sha256;
        private int count;

        public Assembler(String sha256, int totalBytes) {
            if (totalBytes <= 0 || totalBytes > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("Invalid image transfer size");
            }
            this.sha256 = sha256;
            this.bytes = new byte[totalBytes];
            this.received = new boolean[(totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES];
        }

        public String sha256() {
            return sha256;
        }

        public int totalBytes() {
            return bytes.length;
        }

        public int chunkCount() {
            return received.length;
        }

        public synchronized boolean accept(int index, byte[] data) {
            if (index < 0 || index >= received.length || received[index]) {
                return false;
            }
            int offset = index * CHUNK_BYTES;
            int expected = Math.min(CHUNK_BYTES, bytes.length - offset);
            if (data.length != expected) {
                return false;
            }
            System.arraycopy(data, 0, bytes, offset, data.length);
            received[index] = true;
            count++;
            return true;
        }

        public synchronized boolean complete() {
            return count == received.length;
        }

        public synchronized byte[] finish() {
            if (!complete()) {
                throw new IllegalStateException("Image transfer is incomplete");
            }
            return bytes;
        }
    }

    /** Splits a master PNG into protocol-sized slices. */
    public static byte[] chunk(byte[] png, int index) {
        int offset = index * CHUNK_BYTES;
        int length = Math.min(CHUNK_BYTES, png.length - offset);
        byte[] data = new byte[length];
        System.arraycopy(png, offset, data, 0, length);
        return data;
    }

    public static int chunkCount(int totalBytes) {
        return (totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES;
    }

    public static List<EntryData> metadata(PaintingLibrary library) {
        return library.entries().stream().map(EntryData::from).toList();
    }
}
