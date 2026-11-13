package com.mattermod.network.payload;

import com.mattermod.MatterMod;

import io.netty.buffer.ByteBuf;

import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import org.joml.Vector3f;

/**
 * Server → nearby clients: one left-click effect to draw (see LeftClickFx). The left clicks are
 * simulated on the server, so their visuals are drawn on the client in the same pixel-cube style
 * as the right-click shots instead of with vanilla particles.
 */
public record LeftClickFxPayload(int kind, int id, int owner, Vector3f pos, int extra)
        implements CustomPacketPayload {

    /** A gust grenade's position this tick; extra = fuse left, or -1 while flying. */
    public static final int GRENADE = 0;

    /** A gust grenade burst at pos. */
    public static final int BURST = 1;

    /** A tether pull from owner to pos; extra = the pulled entity's id, or -1. */
    public static final int PULL = 2;

    /** A poison puff at pos. */
    public static final int PUFF = 3;

    /** A Botanic harvest cut through the crop at pos; extra = the sweep's heading, in tenths of a degree. */
    public static final int CUT = 4;

    public static final Type<LeftClickFxPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, "left_click_fx"));

    public static final StreamCodec<ByteBuf, LeftClickFxPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    LeftClickFxPayload::kind,
                    ByteBufCodecs.VAR_INT,
                    LeftClickFxPayload::id,
                    ByteBufCodecs.VAR_INT,
                    LeftClickFxPayload::owner,
                    ByteBufCodecs.VECTOR3F,
                    LeftClickFxPayload::pos,
                    ByteBufCodecs.VAR_INT,
                    LeftClickFxPayload::extra,
                    LeftClickFxPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
