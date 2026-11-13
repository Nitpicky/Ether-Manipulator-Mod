package com.mattermod.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;

public final class GeyserParticle extends TextureSheetParticle {
    private final SpriteSet sprites;

    public GeyserParticle(
            ClientLevel level,
            double x,
            double y,
            double z,
            double dx,
            double dy,
            double dz,
            SpriteSet sprites,
            int kind) {
        super(level, x, y, z);
        this.sprites = sprites;
        xd = dx;
        yd = dy;
        zd = dz;
        hasPhysics = false;
        lifetime = kind == 1 ? 25 : 16;
        quadSize = kind == 1 ? .65f : kind == 0 ? 1.1f : .8f;
        friction = .96f;
        gravity = kind == 1 ? .025f : 0;
        setSpriteFromAge(sprites);
        setColor(.85f, .95f, 1f);
    }

    @Override
    public void tick() {
        super.tick();
        setSpriteFromAge(sprites);
        alpha = Math.min(1f, (lifetime - age) / 5f);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }
}
