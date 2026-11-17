package com.ethermod.registry;

import com.ethermod.EtherMod;
import com.ethermod.beam.BeamBallEntity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModEntities {

    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(BuiltInRegistries.ENTITY_TYPE, EtherMod.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<BeamBallEntity>> BEAM_BALL =
            ENTITY_TYPES.register(
                    "beam_ball",
                    () ->
                            EntityType.Builder.<BeamBallEntity>of(
                                            BeamBallEntity::new, MobCategory.MISC)
                                    .sized(0.3f, 0.3f)
                                    .clientTrackingRange(64)
                                    .updateInterval(1) // sync every tick for smooth visual
                                    .build("beam_ball"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.ethermod.beam.LightDecoy>>
            LIGHT_DECOY =
                    ENTITY_TYPES.register(
                            "light_decoy",
                            () ->
                                    EntityType.Builder.<com.ethermod.beam.LightDecoy>of(
                                                    com.ethermod.beam.LightDecoy::new,
                                                    MobCategory.MISC)
                                            .sized(.6f, 1.8f)
                                            .clientTrackingRange(64)
                                            .build("light_decoy"));
    public static final DeferredHolder<
                    EntityType<?>, EntityType<com.ethermod.entity.SoulBlazeling>>
            SOUL_BLAZELING =
                    ENTITY_TYPES.register(
                            "soul_blazeling",
                            () ->
                                    EntityType.Builder.<com.ethermod.entity.SoulBlazeling>of(
                                                    com.ethermod.entity.SoulBlazeling::new,
                                                    MobCategory.CREATURE)
                                            .sized(.8f, .5f)
                                            .eyeHeight(.3f)
                                            .fireImmune()
                                            .clientTrackingRange(8)
                                            .updateInterval(2)
                                            .build("soul_blazeling"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.ethermod.entity.SoulBolt>>
            SOUL_BOLT =
                    ENTITY_TYPES.register(
                            "soul_bolt",
                            () ->
                                    EntityType.Builder.<com.ethermod.entity.SoulBolt>of(
                                                    com.ethermod.entity.SoulBolt::new,
                                                    MobCategory.MISC)
                                            .sized(.3125f, .3125f)
                                            .fireImmune()
                                            .clientTrackingRange(8)
                                            .updateInterval(1)
                                            .build("soul_bolt"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.ethermod.entity.RedstoneGolem>>
            REDSTONE_GOLEM =
                    ENTITY_TYPES.register(
                            "redstone_golem",
                            () ->
                                    EntityType.Builder.<com.ethermod.entity.RedstoneGolem>of(
                                                    com.ethermod.entity.RedstoneGolem::new, MobCategory.MISC)
                                            .sized(2.0f, 2.4f)
                                            .eyeHeight(1.5f)
                                            .fireImmune()
                                            .clientTrackingRange(10)
                                            .build("redstone_golem"));
    public static final DeferredHolder<EntityType<?>, EntityType<com.ethermod.entity.RedstoneBolt>>
            REDSTONE_BOLT =
                    ENTITY_TYPES.register(
                            "redstone_bolt",
                            () ->
                                    EntityType.Builder.<com.ethermod.entity.RedstoneBolt>of(
                                                    com.ethermod.entity.RedstoneBolt::new, MobCategory.MISC)
                                            .sized(.25f, .25f)
                                            .clientTrackingRange(6)
                                            .updateInterval(2)
                                            .build("redstone_bolt"));

    public static final DeferredHolder<EntityType<?>, EntityType<com.ethermod.entity.Ministrosity>>
            MINISTROSITY =
                    ENTITY_TYPES.register(
                            "ministrosity",
                            () ->
                                    EntityType.Builder.<com.ethermod.entity.Ministrosity>of(
                                                    com.ethermod.entity.Ministrosity::new, MobCategory.MISC)
                                            .sized(.6f, 1.05f)
                                            .eyeHeight(.8f)
                                            .fireImmune()
                                            .clientTrackingRange(8)
                                            .build("ministrosity"));

    public static void register(IEventBus bus) {
        bus.addListener(
                (net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) ->
                        event.put(
                                MINISTROSITY.get(),
                                com.ethermod.entity.Ministrosity.attributes().build()));
        bus.addListener(
                (net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) ->
                        event.put(
                                REDSTONE_GOLEM.get(),
                                com.ethermod.entity.RedstoneGolem.attributes().build()));
        bus.addListener(
                (net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) ->
                        event.put(
                                SOUL_BLAZELING.get(),
                                com.ethermod.entity.SoulBlazeling.attributes().build()));
        bus.addListener(
                (net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent event) ->
                        event.put(
                                LIGHT_DECOY.get(),
                                com.ethermod.beam.LightDecoy.attributes().build()));
        ENTITY_TYPES.register(bus);
    }
}
