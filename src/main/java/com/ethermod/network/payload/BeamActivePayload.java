package com.ethermod.network.payload;

import com.ethermod.EtherMod;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → Server
 *
 * <p>Sent when the player starts (active=true) or stops (active=false) holding LMB in BEAM mode.
 *
 * <p>This drives ModEvents.setBeamActive() on the server so the server-side beam tick knows when to
 * fire BeamCastHelper.
 */
public record BeamActivePayload(boolean active) implements CustomPacketPayload {

    public static final Type<BeamActivePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(EtherMod.MODID, "beam_active"));

    public static final StreamCodec<FriendlyByteBuf, BeamActivePayload> CODEC =
            StreamCodec.of(
                    (buf, p) -> buf.writeBoolean(p.active()),
                    buf -> new BeamActivePayload(buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
