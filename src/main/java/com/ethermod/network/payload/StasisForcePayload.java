package com.ethermod.network.payload;

import com.ethermod.EtherMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server → players near it: the knockback a creature in Stasis has stored so far (blocks/tick). */
public record StasisForcePayload(int entity, float x, float y, float z) implements CustomPacketPayload {

    public static final Type<StasisForcePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EtherMod.MODID, "stasis_force"));

    public static final net.minecraft.network.codec.StreamCodec<ByteBuf, StasisForcePayload> CODEC =
            net.minecraft.network.codec.StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, StasisForcePayload::entity,
                    ByteBufCodecs.FLOAT, StasisForcePayload::x,
                    ByteBufCodecs.FLOAT, StasisForcePayload::y,
                    ByteBufCodecs.FLOAT, StasisForcePayload::z,
                    StasisForcePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
