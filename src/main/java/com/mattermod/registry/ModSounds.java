package com.mattermod.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The Redstone Golem's and Ministrosities' sounds (assets/mattermod/sounds.json, golem.* and mini.*). */
public final class ModSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, "mattermod");

    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_STEP = reg("golem.step");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_GALLOP = reg("golem.gallop");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_AMBIENT = reg("golem.ambient");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_LAUGH = reg("golem.laugh");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_HURT = reg("golem.hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_DEATH = reg("golem.death");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_AWAKEN = reg("golem.awaken");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_LAND = reg("golem.land");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_VOICE_SHORT = reg("golem.voice_short");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_SWIPE = reg("golem.swipe");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_ARM = reg("golem.arm");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_CHARGE = reg("golem.charge");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_SLAM = reg("golem.slam");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_LOAD = reg("golem.load");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_SHOOT = reg("golem.shoot");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_OVERHEAT = reg("golem.overheat");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_SCREAM = reg("golem.scream");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_SPARKS = reg("golem.sparks");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_PULSE = reg("golem.pulse");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_SPARK_HIT = reg("golem.spark_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_MALFUNCTION = reg("golem.malfunction");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_POWER_DOWN = reg("golem.power_down");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_EYES = reg("golem.eyes");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_BOLT = reg("golem.bolt");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINI_STEP = reg("mini.step");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINI_AMBIENT = reg("mini.ambient");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINI_SPAWN = reg("mini.spawn");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINI_ATTACK = reg("mini.attack");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINI_SWING = reg("mini.swing");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINI_HURT = reg("mini.hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINI_DEATH = reg("mini.death");

    private static DeferredHolder<SoundEvent, SoundEvent> reg(String id) {
        return SOUNDS.register(id, () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("mattermod", id)));
    }

    public static void register(IEventBus bus) {
        SOUNDS.register(bus);
    }
}
