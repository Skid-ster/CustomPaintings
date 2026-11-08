package com.jay.custompaintings.library;

import com.jay.custompaintings.CustomPaintingsMod;
import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.world.entity.decoration.painting.Painting;

import java.util.OptionalInt;

/**
 * Where a painting's identity lives.
 *
 * <p>Items carry {@link #PAINTING_ID} as a normal data component, so a single registered item
 * can represent unlimited paintings. Placed entities carry the same id in a persistent,
 * automatically synced Fabric attachment, which survives saves and is sent to every tracking
 * client without any custom entity packet.</p>
 */
public final class PaintingData {
    /** Painting id on an item stack. Absent means an unassigned/placeholder stack. */
    public static final DataComponentType<Integer> PAINTING_ID = DataComponentType.<Integer>builder()
            .persistent(Codec.INT)
            .networkSynchronized(ByteBufCodecs.VAR_INT)
            .cacheEncoding()
            .build();

    /** Painting id on a placed entity: persisted to NBT and synced to all tracking clients. */
    public static final AttachmentType<Integer> ENTITY_PAINTING_ID = AttachmentRegistry.<Integer>builder()
            .persistent(Codec.INT)
            .syncWith(ByteBufCodecs.VAR_INT, AttachmentSyncPredicate.all())
            .buildAndRegister(CustomPaintingsMod.id("painting_id"));

    private PaintingData() {
    }

    public static void register() {
        Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, CustomPaintingsMod.id("painting_id"), PAINTING_ID);
    }

    /**
     * Fabric adds {@link AttachmentTarget} to {@code Entity} with a mixin, so the interface is
     * only present at runtime; the cast keeps this compiling against vanilla classes.
     */
    private static AttachmentTarget target(Painting painting) {
        return (AttachmentTarget) (Object) painting;
    }

    /** The painting id carried by a placed entity, if it is one of ours. */
    public static OptionalInt entityPaintingId(Painting painting) {
        Integer id = target(painting).getAttached(ENTITY_PAINTING_ID);
        return id == null || id < 0 ? OptionalInt.empty() : OptionalInt.of(id);
    }

    public static void setEntityPaintingId(Painting painting, int id) {
        target(painting).setAttached(ENTITY_PAINTING_ID, id);
    }
}
