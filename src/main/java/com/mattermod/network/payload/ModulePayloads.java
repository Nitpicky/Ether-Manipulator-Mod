package com.mattermod.network.payload;

import com.mattermod.MatterMod;

import io.netty.buffer.ByteBuf;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Payloads of the modules' movement and marker traits. */
public final class ModulePayloads {

    private ModulePayloads() {}

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String path) {
        return new CustomPacketPayload.Type<>(
                ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, path));
    }

    public record WaterCurrent(java.util.List<BlockPos> positions, float x, float z, long expires)
            implements CustomPacketPayload {
        public static final Type<WaterCurrent> TYPE = typeOf("water_current");
        public static final StreamCodec<ByteBuf, WaterCurrent> CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(256)),
                        WaterCurrent::positions,
                        ByteBufCodecs.FLOAT,
                        WaterCurrent::x,
                        ByteBufCodecs.FLOAT,
                        WaterCurrent::z,
                        ByteBufCodecs.VAR_LONG,
                        WaterCurrent::expires,
                        WaterCurrent::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SoulFlame(int entity, boolean soul) implements CustomPacketPayload {
        public static final Type<SoulFlame> TYPE = typeOf("soul_flame");
        public static final StreamCodec<ByteBuf, SoulFlame> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT,
                        SoulFlame::entity,
                        ByteBufCodecs.BOOL,
                        SoulFlame::soul,
                        SoulFlame::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client to server: Gale Step, a jump in mid-air. */
    public record AirJump() implements CustomPacketPayload {
        public static final Type<AirJump> TYPE = typeOf("air_jump");
        public static final StreamCodec<ByteBuf, AirJump> CODEC = StreamCodec.unit(new AirJump());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Motion(int flags) implements CustomPacketPayload {
        public static final Type<Motion> TYPE = typeOf("motion");
        public static final StreamCodec<ByteBuf, Motion> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, Motion::flags, Motion::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record Gravity(int entity, int down, long start) implements CustomPacketPayload {
        public Gravity(int entity, int down) {
            this(entity, down, -1000);
        }

        public static final Type<Gravity> TYPE = typeOf("gravity");
        public static final StreamCodec<ByteBuf, Gravity> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT,
                        Gravity::entity,
                        ByteBufCodecs.VAR_INT,
                        Gravity::down,
                        ByteBufCodecs.VAR_LONG,
                        Gravity::start,
                        Gravity::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server to client: the player's Lodestar marker. */
    public record LodestarMark(BlockPos pos, ResourceLocation dim, long expires)
            implements CustomPacketPayload {
        public static final Type<LodestarMark> TYPE = typeOf("lodestar");
        public static final StreamCodec<ByteBuf, LodestarMark> CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC,
                        LodestarMark::pos,
                        ResourceLocation.STREAM_CODEC,
                        LodestarMark::dim,
                        ByteBufCodecs.VAR_LONG,
                        LodestarMark::expires,
                        LodestarMark::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
