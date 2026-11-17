package com.ethermod.network.payload;

import com.ethermod.EtherMod;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/** Client → Server payloads of the upgrade screen. */
public final class TraitPayloads {

    private TraitPayloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String path) {
        return new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(EtherMod.MODID, path));
    }

    /** Equip a line in a slot; empty clears the slot. */
    public record Equip(int slot, Optional<ResourceLocation> line) implements CustomPacketPayload {
        public static final Type<Equip> TYPE = typeOf("trait_equip");
        public static final StreamCodec<ByteBuf, Equip> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT,
                        Equip::slot,
                        ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC),
                        Equip::line,
                        Equip::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Choose trait {@code choice} (0-2) of {@code tier} (0-2) in a slot. */
    public record Pick(int slot, int tier, int choice) implements CustomPacketPayload {
        public static final Type<Pick> TYPE = typeOf("trait_pick");
        public static final StreamCodec<ByteBuf, Pick> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT,
                        Pick::slot,
                        ByteBufCodecs.VAR_INT,
                        Pick::tier,
                        ByteBufCodecs.VAR_INT,
                        Pick::choice,
                        Pick::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Install the next upgrade module from the inventory. */
    public record StageUpgrade() implements CustomPacketPayload {
        public static final Type<StageUpgrade> TYPE = typeOf("stage_upgrade");
        public static final StreamCodec<ByteBuf, StageUpgrade> CODEC =
                StreamCodec.unit(new StageUpgrade());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Unlock a colour for good with the item in inventory slot {@code invSlot} (a dyed Amethyst
     * Shard for the beam, a dye for the trim), using one up, and switch to it.
     */
    public record Paint(boolean body, int invSlot) implements CustomPacketPayload {
        public static final Type<Paint> TYPE = typeOf("paint");
        public static final StreamCodec<ByteBuf, Paint> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL,
                        Paint::body,
                        ByteBufCodecs.VAR_INT,
                        Paint::invSlot,
                        Paint::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Pick an unlocked colour: beam (rgb) or trim (rgb, or -1 for the original yellow). */
    public record PickColour(boolean body, int rgb) implements CustomPacketPayload {
        public static final Type<PickColour> TYPE = typeOf("pick_colour");
        public static final StreamCodec<ByteBuf, PickColour> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL,
                        PickColour::body,
                        ByteBufCodecs.INT,
                        PickColour::rgb,
                        PickColour::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** A loadout operation (see Loadouts): op, list index, name. */
    public record Loadout(int op, int index, String name) implements CustomPacketPayload {
        public static final Type<Loadout> TYPE = typeOf("loadout");
        public static final StreamCodec<ByteBuf, Loadout> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT,
                        Loadout::op,
                        ByteBufCodecs.VAR_INT,
                        Loadout::index,
                        ByteBufCodecs.stringUtf8(64),
                        Loadout::name,
                        Loadout::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
