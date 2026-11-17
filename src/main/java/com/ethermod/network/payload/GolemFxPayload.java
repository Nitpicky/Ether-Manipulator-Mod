package com.ethermod.network.payload;

import com.ethermod.EtherMod;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Server → clients tracking a Redstone Golem: an effect to draw. CHAIN: a Supercharged arc chain, the points (x, y, z)
 * it jumps through in order. FIELD_HIT: a projectile stopped by its Cube Field, at one point (x, y, z).
 */
public record GolemFxPayload(int golemId, int kind, List<Float> points) implements CustomPacketPayload {

    public static final int CHAIN = 0, FIELD_HIT = 1;

    public static final Type<GolemFxPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EtherMod.MODID, "golem_fx"));

    public static final StreamCodec<ByteBuf, GolemFxPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    GolemFxPayload::golemId,
                    ByteBufCodecs.VAR_INT,
                    GolemFxPayload::kind,
                    ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list(16)),
                    GolemFxPayload::points,
                    GolemFxPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
