package com.mattermod.network.payload;

import com.mattermod.MatterMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Server → owning client: the player's Manipulator stack and whether it is summoned. */
public record ManipulatorSyncPayload(boolean summoned, ItemStack stack)
        implements CustomPacketPayload {

    public static final Type<ManipulatorSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, "manipulator_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ManipulatorSyncPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    ManipulatorSyncPayload::summoned,
                    ItemStack.OPTIONAL_STREAM_CODEC,
                    ManipulatorSyncPayload::stack,
                    ManipulatorSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
