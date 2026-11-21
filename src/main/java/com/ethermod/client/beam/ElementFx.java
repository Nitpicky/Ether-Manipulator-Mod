package com.ethermod.client.beam;

import com.ethermod.beam.BeamBallEntity;

import com.ethermod.item.OrbMode;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Each Module's element rides on top of its tool animation, the way Plasmic sparks ride on the beam
 * (chosen on the FX bench): while an orb charges, and for a moment after it is fired, the nozzle
 * sheds a few vanilla particles of its element. Tectonic has no nozzle particles: it gets the grip,
 * a glow shell around each block the Mass Driver holds and a faint line from the nozzle to it.
 */
@OnlyIn(Dist.CLIENT)
final class ElementFx {

    private ElementFx() {}

    /** One particle kind: per tick, speed out of the barrel, upward drift, drawn into the nozzle. */
    private record Kind(ParticleOptions type, float perTick, float speed, float up, boolean in) {}

    private static final float[] DEBT = new float[2];
    private static int tail;
    private static final RandomSource RNG = RandomSource.create();

    private static Kind[] kinds(OrbMode m) {
        return switch (m) {
            case ENERGY -> {
                int c = 0x20C1E9; // Kinetic motes drawn into the nozzle as it charges
                yield new Kind[] {
                    new Kind(new DustParticleOptions(new Vector3f(((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f), 0.6f), 0.7f, 0.02f, 0f, true)
                };
            }
            case WATER -> new Kind[] {new Kind(ParticleTypes.BUBBLE_POP, 0.45f, 0.02f, 0.02f, false)};
            case FROST -> new Kind[] {new Kind(ParticleTypes.SNOWFLAKE, 0.45f, 0.03f, -0.01f, false)};
            case LAVA -> new Kind[] {
                new Kind(ParticleTypes.SMOKE, 0.35f, 0.02f, 0.04f, false), new Kind(ParticleTypes.LAVA, 0.06f, 0f, 0f, false)
            };
            case IGNITE -> new Kind[] {
                new Kind(ParticleTypes.FLAME, 0.3f, 0.01f, 0.02f, false), new Kind(ParticleTypes.SMOKE, 0.2f, 0.02f, 0.04f, false)
            };
            case GUST -> new Kind[] {new Kind(ParticleTypes.CLOUD, 0.18f, 0.05f, 0.01f, false)};
            case LIGHT -> new Kind[] {new Kind(ParticleTypes.END_ROD, 0.3f, 0.02f, 0.01f, false)};
            case TETHER -> new Kind[] {new Kind(ParticleTypes.REVERSE_PORTAL, 0.5f, 0.02f, 0f, false)};
            case GROWTH -> new Kind[] {new Kind(ParticleTypes.SPORE_BLOSSOM_AIR, 0.4f, 0.02f, 0f, false)};
            default -> new Kind[0];
        };
    }

    /** Called every frame with the local player's nozzle (world space) and barrel direction. */
    static void frame(Minecraft mc, Player player, ItemStack held, Vec3 nozzle, Vec3 barrel, Vec3 cam, float time, float dt, float pt, VertexConsumer vc, Matrix4f m) {
        reloadMotes(mc, nozzle, barrel, dt);
        OrbMode mode = OrbMode.get(held);
        if (mode == OrbMode.MASS) {
            grip(mc, player, nozzle, cam, time, pt, vc, m);
            return;
        }
        if (BeamRenderState.isCharging) tail = 10; // half a second after the shot
        if (tail <= 0 || mc.isPaused()) return;
        Kind[] ks = kinds(mode);
        for (int i = 0; i < ks.length && i < DEBT.length; i++) {
            Kind k = ks[i];
            DEBT[i] += k.perTick * dt * 20f;
            while (DEBT[i] >= 1f) {
                DEBT[i] -= 1f;
                Vec3 o = nozzle.add(barrel.scale(k.in ? 0.45 : 0.12)).add(r(0.12), r(0.12), r(0.12));
                Vec3 v = k.in ? nozzle.subtract(o).scale(0.15) : barrel.scale(k.speed).add(r(0.02), k.up, r(0.02));
                mc.level.addParticle(k.type, o.x, o.y, o.z, v.x, v.y, v.z);
            }
        }
    }

    private static float etherDebt;
    private static final DustParticleOptions ETHER =
            new DustParticleOptions(new Vector3f(0x30 / 255f, 0xD5 / 255f, 0xC8 / 255f), 0.7f);

    /** Reload: motes of Ether (teal, whatever the mode colour) drawn into the nozzle before the clamp. */
    private static void reloadMotes(Minecraft mc, Vec3 nozzle, Vec3 barrel, float dt) {
        float rt = com.ethermod.client.ManipulatorAnimations.reloadTime();
        if (rt < 0.1f || rt > 0.82f || mc.isPaused()) return;
        etherDebt += 1.2f * dt * 20f;
        while (etherDebt >= 1f) {
            etherDebt -= 1f;
            Vec3 o = nozzle.add(barrel.scale(0.5)).add(r(0.25), r(0.25), r(0.25));
            Vec3 v = nozzle.subtract(o).scale(0.18);
            mc.level.addParticle(ETHER, o.x, o.y, o.z, v.x, v.y, v.z);
        }
    }

    /** Counts the half second after a shot down; called once per client tick. */
    static void tick() {
        if (!BeamRenderState.isCharging && tail > 0) tail--;
    }

    private static double r(double a) {
        return (RNG.nextDouble() * 2 - 1) * a;
    }

    /** Tectonic: the Manipulator's grip on each held block, in the Tectonic colour, breathing. */
    private static void grip(Minecraft mc, Player player, Vec3 nozzle, Vec3 cam, float time, float pt, VertexConsumer vc, Matrix4f m) {
        int c = 0xD48050;
        float r = ((c >> 16) & 255) / 255f, g = ((c >> 8) & 255) / 255f, b = (c & 255) / 255f;
        float br = 0.5f + 0.5f * (float) Math.sin(time / 20f * 6f);
        for (var e : mc.level.entitiesForRendering()) {
            if (!(e instanceof BeamBallEntity ball)
                    || ball.getMode() != OrbMode.MASS
                    || !ball.isMassHeld()
                    || ball.getOwner() != player
                    || ball.getCarried().isAir()) continue;
            Vec3 p = ball.getPosition(pt).subtract(cam);
            // the shell turns exactly like the drawn block (BeamBallRenderer: yaw, then pitch)
            var q = new org.joml.Quaternionf()
                    .rotateY((float) Math.toRadians(ball.getYRot()))
                    .rotateX((float) Math.toRadians(ball.getXRot()));
            // kept light: the block must stay readable through it
            shell(vc, m, p, q, 0.53f, r, g, b, 0.04f + 0.03f * br);
            shell(vc, m, p, q, 0.56f, r, g, b, 0.02f * br);
            if (ball.massOffset().equals(net.minecraft.core.BlockPos.ZERO))
                BeamFx.prism(vc, m, nozzle.subtract(cam), p, BeamFx.PX * 0.2f, 0.785, r, g, b, 0.14f + 0.08f * br);
        }
    }

    private static void shell(VertexConsumer vc, Matrix4f m, Vec3 c, org.joml.Quaternionf q, float h, float r, float g, float b, float a) {
        var x = q.transform(new org.joml.Vector3f(h, 0, 0));
        var y = q.transform(new org.joml.Vector3f(0, h, 0));
        var z = q.transform(new org.joml.Vector3f(0, 0, h));
        BeamFx.box(vc, m, c, new Vec3(x.x, x.y, x.z), new Vec3(y.x, y.y, y.z), new Vec3(z.x, z.y, z.z), r, g, b, a);
    }
}
