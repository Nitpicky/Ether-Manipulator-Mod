package com.mattermod.advancement;

import com.mattermod.MatterMod;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.advancements.CriterionTrigger;
import net.minecraft.advancements.critereon.ContextAwarePredicate;
import net.minecraft.advancements.critereon.EntityPredicate;
import net.minecraft.advancements.critereon.SimpleCriterionTrigger;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Optional;

/**
 * One advancement trigger for everything the Manipulator does that vanilla criteria can't see: {@code
 * mattermod:manipulator} with an {@code event} string ("summon", "stage_iron", "stage_diamond",
 * "stage_netherite", "dye", "all_dyes"). New advancements only need a new event name.
 */
public final class ManipulatorTrigger extends SimpleCriterionTrigger<ManipulatorTrigger.Instance> {

    private static final DeferredRegister<CriterionTrigger<?>> TRIGGERS =
            DeferredRegister.create(Registries.TRIGGER_TYPE, MatterMod.MODID);

    public static final DeferredHolder<CriterionTrigger<?>, ManipulatorTrigger> INSTANCE =
            TRIGGERS.register("manipulator", ManipulatorTrigger::new);

    public static void register(IEventBus bus) {
        TRIGGERS.register(bus);
    }

    /** Fires the event for this player. Safe to call often: vanilla ignores already-earned criteria. */
    public static void fire(ServerPlayer player, String event) {
        INSTANCE.get().trigger(player, i -> i.event().map(event::equals).orElse(true));
    }

    @Override
    public Codec<Instance> codec() {
        return Instance.CODEC;
    }

    public record Instance(Optional<ContextAwarePredicate> player, Optional<String> event)
            implements SimpleCriterionTrigger.SimpleInstance {
        public static final Codec<Instance> CODEC =
                RecordCodecBuilder.create(
                        i ->
                                i.group(
                                                EntityPredicate.ADVANCEMENT_CODEC
                                                        .optionalFieldOf("player")
                                                        .forGetter(Instance::player),
                                                Codec.STRING
                                                        .optionalFieldOf("event")
                                                        .forGetter(Instance::event))
                                        .apply(i, Instance::new));
    }
}
