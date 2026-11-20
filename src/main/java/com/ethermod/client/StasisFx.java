package com.ethermod.client;

import com.ethermod.sound.ModSounds;
import com.mojang.blaze3d.vertex.PoseStack;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * The Stasis Grenade's hold, seen and heard: the held creature takes Pneumatic's teal tint that
 * pulses, and the grenade's fuse tick (with a soft ding under it) quickens until it lets go (a
 * time-lock you can read without a timer). The release is the grenade's own wind burst.
 */
public final class StasisFx {

    private StasisFx() {}

    private record Hold(long start, long end) {}

    private static final Map<Integer, Hold> HELD = new HashMap<>();
    private static final Map<Integer, Long> NEXT_DING = new HashMap<>();

    /** From the server: held for ticks (0 = let go now). */
    public static void mark(int entity, int ticks) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long now = mc.level.getGameTime();
        if (ticks <= 0) {
            release(entity);
            return;
        }
        HELD.put(entity, new Hold(now, now + ticks));
        FORCE.remove(entity);
        NEXT_DING.put(entity, now);
    }

    /** Stored knockback per held creature, from the server. */
    private static final Map<Integer, net.minecraft.world.phys.Vec3> FORCE = new HashMap<>();

    public static void force(int entity, float x, float y, float z) {
        FORCE.put(entity, new net.minecraft.world.phys.Vec3(x, y, z));
    }

    /**
     * The knockback arrow: a teal rope like the Tether's (a soft outer prism around a bright core),
     * from the creature's body along the push it has stored, with two short rope strokes as the
     * head. It grows with the stored force.
     */
    public static void renderArrows(Minecraft mc, net.minecraft.world.phys.Vec3 cam, float pt, float time,
            com.mojang.blaze3d.vertex.VertexConsumer vc, org.joml.Matrix4f m) {
        if (mc.level == null || FORCE.isEmpty()) return;
        for (var en : FORCE.entrySet()) {
            if (!HELD.containsKey(en.getKey()) || !(mc.level.getEntity(en.getKey()) instanceof LivingEntity e)) continue;
            var v = en.getValue();
            double force = v.length();
            if (force < .05) continue;
            var dir = v.scale(1 / force);
            var centre = e.getPosition(pt).add(0, e.getBbHeight() * .5, 0);
            var from = centre.add(dir.scale(e.getBbWidth() * .5 + .15));
            var tip = from.add(dir.scale(.4 + .5 * force)); // 1.2 blocks for a bare burst, 1.9 at the cap
            var side = dir.cross(new net.minecraft.world.phys.Vec3(0, 1, 0));
            if (side.lengthSqr() < 1e-4) side = dir.cross(new net.minecraft.world.phys.Vec3(1, 0, 0));
            side = side.normalize();
            float p = Math.max(0, pulse(e, pt));
            var a = from.subtract(cam);
            var t = tip.subtract(cam);
            var h1 = tip.add(dir.scale(-.4)).add(side.scale(.28)).subtract(cam);
            var h2 = tip.add(dir.scale(-.4)).add(side.scale(-.28)).subtract(cam);
            for (var seg : new net.minecraft.world.phys.Vec3[][] {{a, t}, {t, h1}, {t, h2}}) {
                com.ethermod.client.beam.BeamFx.prism(vc, m, seg[0], seg[1], com.ethermod.client.beam.BeamFx.PX * 1.4f, time * .1,
                        TEAL_R, TEAL_G, TEAL_B, .3f + .1f * p);
                com.ethermod.client.beam.BeamFx.prism(vc, m, seg[0], seg[1], com.ethermod.client.beam.BeamFx.PX * .5f, time * .1,
                        .73f, .95f, .84f, .95f);
            }
        }
    }

    private static final float TEAL_R = 0x20 / 255f, TEAL_G = 0xE9 / 255f, TEAL_B = 0xC1 / 255f;

    private static void release(int entity) {
        var mc = Minecraft.getInstance();
        HELD.remove(entity);
        NEXT_DING.remove(entity);
        FORCE.remove(entity);
    }

    /** Client tick: dings, faster as the hold runs out; ends holds whose time is up. */
    public static void tick() {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            HELD.clear();
            NEXT_DING.clear();
            FORCE.clear();
            return;
        }
        long now = mc.level.getGameTime();
        for (var id : HELD.keySet().toArray(new Integer[0])) {
            Hold h = HELD.get(id);
            Entity e = mc.level.getEntity(id);
            if (e == null || !e.isAlive() || now >= h.end) {
                release(id);
                continue;
            }
            if (now >= NEXT_DING.getOrDefault(id, now)) {
                float left = (h.end - now) / (float) Math.max(1, h.end - h.start); // 1 -> 0
                float pitch = 1.4f + 0.5f * (1 - left);
                play(e, ModSounds.WORLD_NOTE_BLOCK_HAT, 0.6f, pitch); // the grenade's fuse tick
                play(e, ModSounds.WORLD_STASIS_DING, 0.18f, pitch); // with the hold's ding under it
                NEXT_DING.put(id, now + Math.max(2, Math.round(2 + 6 * left))); // 8 ticks apart, then 2
            }
        }
    }

    private static void play(Entity e, net.minecraft.sounds.SoundEvent ev, float vol, float pitch) {
        Minecraft.getInstance().getSoundManager().play(
                new SimpleSoundInstance(ev, SoundSource.NEUTRAL, vol, pitch, RandomSource.create(), e.getX(), e.getY(0.5), e.getZ()));
    }

    /** 0..1 pulse brightness of the tint for this entity, or -1 when it isn't held. */
    static float pulse(LivingEntity e, float pt) {
        Hold h = HELD.get(e.getId());
        if (h == null) return -1;
        float now = e.level().getGameTime() + pt;
        float left = Math.max(0, (h.end - now) / Math.max(1, h.end - h.start));
        // the glow pulses with the dings: slow while there is time, quick at the end
        float rate = 0.25f + 1.1f * (1 - left);
        float p = 0.5f + 0.5f * (float) Math.sin((now - h.start) * rate);
        if (com.ethermod.config.ClientConfig.reduceFlashing()) p = 0.5f + 0.15f * (p - 0.5f);
        return p;
    }

    /** Drawn over every living creature's model: the teal tint while held. */
    public static final class Layer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
        private final LivingEntityRenderer<T, M> parent;

        public Layer(LivingEntityRenderer<T, M> parent) {
            super((RenderLayerParent<T, M>) parent);
            this.parent = parent;
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buf, int light, T e, float limb, float limbAmt, float pt, float age, float yaw, float pitch) {
            if (HELD.isEmpty()) return; // nothing held: skip the lookup for every creature drawn
            float p = pulse(e, pt);
            if (p < 0) return;
            int a = (int) (255 * (0.25f + 0.35f * p));
            int color = (a << 24) | 0x20E9C1; // Pneumatic teal
            var vc = buf.getBuffer(RenderType.entityTranslucentEmissive(parent.getTextureLocation(e)));
            getParentModel().renderToBuffer(pose, vc, 0xF000F0, OverlayTexture.NO_OVERLAY, color);
        }
    }
}
