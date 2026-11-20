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
                                    .build(key("beam_ball")));

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
                                            .build(key("light_decoy")));
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
                                            .build(key("soul_blazeling")));
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
                                            .build(key("soul_bolt")));

    private static net.minecraft.resources.ResourceKey<EntityType<?>> key(String name) {
        return net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.ENTITY_TYPE,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(EtherMod.MODID, name));
    }

    public static void register(IEventBus bus) {
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
