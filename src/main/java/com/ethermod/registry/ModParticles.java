package com.ethermod.registry;

import net.minecraft.core.particles.*;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.*;

public final class ModParticles {
    private static final DeferredRegister<ParticleType<?>> TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, "ethermod");
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> BASE =
            TYPES.register("geyser_base", () -> new SimpleParticleType(false) {});
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> PLUME =
            TYPES.register("geyser_plume", () -> new SimpleParticleType(false) {});
    public static final DeferredHolder<ParticleType<?>, SimpleParticleType> POOF =
            TYPES.register("geyser_poof", () -> new SimpleParticleType(false) {});

    public static void register(net.neoforged.bus.api.IEventBus bus) {
        TYPES.register(bus);
    }
}
