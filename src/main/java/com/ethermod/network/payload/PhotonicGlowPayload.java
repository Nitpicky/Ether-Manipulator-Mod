package com.ethermod.network.payload;

import com.ethermod.EtherMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server → a player and those who see them: whether that player gives off the Photonic glow. */
public record PhotonicGlowPayload(int entity, boolean on) implements CustomPacketPayload {

    public static final Type<PhotonicGlowPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EtherMod.MODID, "photonic_glow"));

    public static final net.minecraft.network.codec.StreamCodec<ByteBuf, PhotonicGlowPayload> CODEC =
            net.minecraft.network.codec.StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PhotonicGlowPayload::entity, ByteBufCodecs.BOOL, PhotonicGlowPayload::on, PhotonicGlowPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
