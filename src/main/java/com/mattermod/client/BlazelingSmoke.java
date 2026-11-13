package com.mattermod.client;

import com.mattermod.entity.SoulBlazeling;

import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;

/** Full-size vanilla smoke, emitted moderately from the lower rod band. */
public final class BlazelingSmoke {
    public static final int INTERVAL = 2, MIN_LIFE = 18, EXTRA_LIFE = 5;
    public static final double MIN_Y = -.06, HEIGHT = .08;

    public static void emit(SoulBlazeling pet) {
        if (pet.tickCount % INTERVAL != 0) return;
        var r = pet.getRandom();
        var particle =
                Minecraft.getInstance()
                        .particleEngine
                        .createParticle(
                                ParticleTypes.LARGE_SMOKE,
                                pet.getRandomX(.35),
                                pet.getY() + MIN_Y + r.nextDouble() * HEIGHT,
                                pet.getRandomZ(.35),
                                0,
                                0,
                                0);
        if (particle != null) {
            particle.setLifetime(MIN_LIFE + r.nextInt(EXTRA_LIFE));
            particle.setParticleSpeed(0, .003, 0);
        }
    }
}
