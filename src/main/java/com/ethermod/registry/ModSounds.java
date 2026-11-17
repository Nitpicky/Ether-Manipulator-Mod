package com.ethermod.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The Redstone Monstrosity's and Ministrosities' sounds (assets/ethermod/sounds.json, golem.* and mini.*), assembled
 *  from the sound bench: files trimmed to their onset; volumes and pitches per moment are in the code that plays them. */
public final class ModSounds {
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, "ethermod");

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
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_SPARK_HIT = reg("golem.spark_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_POWER_DOWN = reg("golem.power_down");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_ARC = reg("golem.arc");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_FIELD_HIT = reg("golem.field_hit");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_FIELD_DROP = reg("golem.field_drop");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_JUMP = reg("golem.jump");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_REPAIR = reg("golem.repair");
    public static final DeferredHolder<SoundEvent, SoundEvent> GOLEM_ROCKET = reg("golem.rocket");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINI_STEP = reg("mini.step");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINI_AMBIENT = reg("mini.ambient");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINI_SPAWN = reg("mini.spawn");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINI_ATTACK = reg("mini.attack");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINI_HURT = reg("mini.hurt");
    public static final DeferredHolder<SoundEvent, SoundEvent> MINI_DEATH = reg("mini.death");

    private static DeferredHolder<SoundEvent, SoundEvent> reg(String id) {
        return SOUNDS.register(id, () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("ethermod", id)));
    }

    public static void register(IEventBus bus) {
        SOUNDS.register(bus);
    }
}
