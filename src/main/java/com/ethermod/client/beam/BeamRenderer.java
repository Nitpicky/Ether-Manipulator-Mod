package com.ethermod.client.beam;

import com.ethermod.beam.BeamTrace;
import com.ethermod.beam.RemoteBeams;

import com.ethermod.item.EtherManipulatorItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.joml.Matrix4f;

import java.util.*;

/**
 * Draws the Manipulator beam for the local player and for every other player whose beam is on
 * (RemoteBeams).
 *
 * <p>Blocky, to match Minecraft: a straight square beam (like a beacon beam) from the centre of the
 * model's nozzle to what you aim at, a white core, pixel "packets" running down it, orbiting pixels
 * at combat levels 2 and 3, a cube flare at both ends and cube sparks. The start point comes from
 * {@link Muzzle}, so it stays on the nozzle when the camera moves.
 */
@OnlyIn(Dist.CLIENT)
public final class BeamRenderer {

    private BeamRenderer() {}

    private static final float PX = BeamFx.PX;

    private static long lastNanos = System.nanoTime();
    private static boolean lastBeamState = false;
    private static Vec3 lastFromW = Vec3.ZERO, lastToW = Vec3.ZERO;

    private static Vec3 localTip, localAim;
    private static final Map<Integer, Vec3> remoteTips = new HashMap<>();
    private static final Map<Integer, Vec3> remoteAims = new HashMap<>();

    /** Drops sparks, crown spikes and remote-beam state when the client changes world or server. */
    public static void clearCaches() {
        BeamImpact.clear();
        remoteTips.clear();
        remoteAims.clear();
        PrismRope.clear();
    }

    /** How far the beam's bend trails the aim: the time to close half the gap. */
    private static final double BEND_HALF_LIFE = 0.06;

    private static Vec3 elasticDir(Vec3 from, Vec3 to, float dt) {
        double k = 1 - Math.pow(0.5, dt / BEND_HALF_LIFE);
        Vec3 v = from.add(to.subtract(from).scale(k));
        return v.lengthSqr() < 1e-9 ? to : v.normalize();
    }

    /** Where a beam's tip is now (owner -1: the local player), or null once it stopped. */
    static Vec3 tipOf(int owner) {
        return owner == -1 ? (lastBeamState ? localTip : null) : remoteTips.get(owner);
    }

    /** The local player's nozzle this frame (world) and barrel direction; null if unknown. */
    public static Vec3 nozzle, barrel;

    /**
     * Frame handling for every effect (see {@link com.ethermod.client.Fx}): start of frame, depth
     * copy before water, then once the world is finished the world's effects are produced and
     * everything recorded this frame is drawn at once.
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // Shader packs fire these again for the shadow map, from the sun's view: drawing
        // there made a second beam come out of the sky (see ShaderCompat)
        if (com.ethermod.client.ShaderCompat.inShadowPass()) return;
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            com.ethermod.client.Fx.beginFrame();
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            com.ethermod.client.Fx.captureDepth();
        }
        if (stage == com.ethermod.client.Fx.drawStage()) {
            com.ethermod.client.Fx.drawFrame(event, () -> produceWorldFx(event));
        }
    }

    /** Records the beam, glows, markers and sparks for this frame. */
    private static void produceWorldFx(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;
        Muzzle.nextFrame();

        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 camPos = camera.getPosition();
        Vec3 camLeft = new Vec3(camera.getLeftVector());
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float time = (float) (mc.level.getGameTime() + pt);
        Matrix4f proj = event.getProjectionMatrix();
        Matrix4f view = event.getModelViewMatrix();

        // Effects are recorded camera-relative, without the camera rotation
        PoseStack ps = new PoseStack();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        Matrix4f m = ps.last().pose();

        boolean fp = mc.options.getCameraType().isFirstPerson() && mc.getCameraEntity() == player;
        Vec3[] nb = Muzzle.resolve(player.getId(), fp, proj, view, camPos);
        if (nb != null) {
            nozzle = nb[0];
            barrel = nb[1];
        } else {
            nozzle = BeamCurve.approximateMuzzle(player, pt);
            barrel = player.getViewVector(pt);
        }

        ItemStack held = player.getMainHandItem();
        if (held.getItem() instanceof EtherManipulatorItem) {
            renderLocal(mc, player, held, camPos, pt, time, dt, ps, buf, m);
            ElementFx.frame(mc, player, held, nozzle, barrel, camPos, time, dt, pt, com.ethermod.client.Fx.glow(), m);
            BlockGlow.render(mc, player, held, camPos, time, pt, buf, m);
            OreSense.render(mc, player, held, camPos, time, buf, m);
        } else if (lastBeamState) {
            lastBeamState = false;
            BeamRenderState.clearBeam();
        }

        VertexConsumer vc = com.ethermod.client.Fx.glow();
        renderRemote(mc, camPos, pt, time, dt, proj, view, vc, m);

        ModuleMarkers.render(mc, camPos, camLeft, time, buf, m);
        LeftClickFx.render(mc, camPos, pt, time, vc, m);
        com.ethermod.client.StasisFx.renderArrows(mc, camPos, pt, time, vc, m);
        if (held.getItem() instanceof EtherManipulatorItem)
            MassPreview.render(mc, player, held, camPos, time, vc, m);

        BeamImpact.updateAndRenderSparks(dt, camPos, vc, m);
    }

    // =========================================================================
    // Local player
    // =========================================================================

    private static void renderLocal(
            Minecraft mc,
            Player player,
            ItemStack held,
            Vec3 camPos,
            float pt,
            float time,
            float dt,
            PoseStack ps,
            MultiBufferSource.BufferSource buf,
            Matrix4f m) {
        boolean beamOn = BeamRenderState.beamActive;
        float[] color = com.ethermod.item.ManipulatorColors.beamRgba(held);

        if (BeamRenderState.isCharging) {
            // The charging orb sits just in front of the nozzle, along the barrel,
            // far enough out that it doesn't clip into the model or the beam.
            Vec3 c = orbAnchor(BeamBallRenderer.previewRadius(), camPos);
            BeamBallRenderer.renderPreview(
                    ps, buf, c.subtract(camPos), BeamRenderState.chargePhase, time, 1f, Vec3.ZERO);
        }

        if (beamOn) {
            HitResult hit =
                    BeamTrace.castWithEntities(
                            player, com.ethermod.trait.Traits.beamRange(held), pt);
            Vec3 rawHit = hit.getLocation();
            boolean hitSomething = hit.getType() != HitResult.Type.MISS;
            float extend = Math.min(1f, (BeamRenderState.beamTicks + pt) / 2.5f);
            int level = RemoteBeams.level(player.getId());
            // The end point follows the aim exactly; the curve's bend trails behind
            // and catches up, so a sweep bends the beam like something elastic
            Vec3 aimNow = player.getViewVector(pt);
            localAim =
                    (!lastBeamState || localAim == null)
                            ? aimNow
                            : elasticDir(localAim, aimNow, dt);
            localTip = rawHit;

            if (com.ethermod.item.OrbMode.get(held) == com.ethermod.item.OrbMode.LIGHT) {
                lightBeacon(mc, buf, m, camPos, nozzle, localTip, extend, time, color);
            } else {
                BeamCurve.drawBeam(
                        player.getId(),
                        m,
                        camPos,
                        nozzle,
                        localAim,
                        localTip,
                        extend,
                        level,
                        time,
                        color,
                        hitSomething);
                BeamCurve.drawArcs(m, camPos, player, held, hit, level, time, color);
            }
            BeamImpact.spawnImpactFx(mc.level, hit, localTip, nozzle, color, level, dt, hitSomething, -1);
            Vec3 toW = localTip;

            lastFromW = nozzle;
            lastToW = toW;
            lastBeamState = true;
        } else if (lastBeamState) {
            BeamImpact.spawnShutdown(lastFromW, lastToW, color);
            lastBeamState = false;
        }
    }

    // =========================================================================
    // Other players
    // =========================================================================

    private static void renderRemote(
            Minecraft mc,
            Vec3 camPos,
            float pt,
            float time,
            float dt,
            Matrix4f proj,
            Matrix4f view,
            VertexConsumer vc,
            Matrix4f m) {
        ClientLevel level = mc.level;
        long gameTime = level.getGameTime();
        // a remote beam that stopped: its rope's roll and arc state go with it
        for (int gone : remoteTips.keySet())
            if (!RemoteBeams.active().containsKey(gone)) PrismRope.forget(gone);
        remoteTips.keySet().retainAll(RemoteBeams.active().keySet());
        remoteAims.keySet().retainAll(RemoteBeams.active().keySet());
        for (Map.Entry<Integer, Long> e : RemoteBeams.active().entrySet()) {
            Entity ent = level.getEntity(e.getKey());
            if (!(ent instanceof Player p) || p == mc.player) continue;
            ItemStack held = p.getMainHandItem();
            if (!(held.getItem() instanceof EtherManipulatorItem)) continue;

            float[] color = com.ethermod.item.ManipulatorColors.beamRgba(held);
            HitResult hit =
                    BeamTrace.castWithEntities(p, com.ethermod.trait.Traits.beamRange(held), pt);
            Vec3 toW = hit.getLocation();
            remoteTips.put(p.getId(), toW);
            Vec3 aimNow = p.getViewVector(pt);
            Vec3 prevAim = remoteAims.get(p.getId());
            Vec3 aim = prevAim == null ? aimNow : elasticDir(prevAim, aimNow, dt);
            remoteAims.put(p.getId(), aim);
            float heldTicks = gameTime - e.getValue() + pt;
            int lvl = RemoteBeams.level(p.getId());
            Vec3[] nb = Muzzle.resolve(p.getId(), false, proj, view, camPos);
            Vec3 fromW = nb != null ? nb[0] : BeamCurve.approximateMuzzle(p, pt);
            boolean hitSomething = hit.getType() != HitResult.Type.MISS;
            if (com.ethermod.item.OrbMode.get(held) == com.ethermod.item.OrbMode.LIGHT) {
                lightBeacon(mc, mc.renderBuffers().bufferSource(), m, camPos, fromW, toW, Math.min(1f, heldTicks / 2.5f), time, color);
                BeamImpact.spawnImpactFx(level, hit, toW, fromW, color, lvl, dt, hitSomething, p.getId());
                continue;
            }
            BeamCurve.drawBeam(
                    p.getId(),
                    m,
                    camPos,
                    fromW,
                    aim,
                    toW,
                    Math.min(1f, heldTicks / 2.5f),
                    lvl,
                    time,
                    color,
                    hitSomething);
            BeamCurve.drawArcs(m, camPos, p, held, hit, lvl, time, color);
            BeamImpact.spawnImpactFx(level, hit, toW, fromW, color, lvl, dt, hitSomething, p.getId());
        }
    }

    private static long lastBeaconMote = Long.MIN_VALUE;

    /** Photonic's beacon in place of the beam (vanilla's beacon columns, FX bench), with a mote of light every 8 ticks. */
    private static void lightBeacon(
            Minecraft mc, MultiBufferSource.BufferSource buf, Matrix4f m, Vec3 camPos, Vec3 fromW, Vec3 toW, float extend, float time, float[] color) {
        Vec3 to = fromW.lerp(toW, extend);
        LightBeacon.draw(buf, m, fromW.subtract(camPos), to.subtract(camPos), time, color);
        long tick = (long) time;
        if (tick % 8 == 0 && tick != lastBeaconMote && !mc.isPaused()) {
            lastBeaconMote = tick;
            Vec3 p = fromW.lerp(to, Math.random());
            mc.level.addParticle(
                    net.minecraft.core.particles.ParticleTypes.END_ROD,
                    p.x, p.y, p.z, (Math.random() - 0.5) * 0.04, 0.01, (Math.random() - 0.5) * 0.04);
        }
    }


    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Where the orb sits for a given radius: ahead of the nozzle along the barrel, far enough that
     * its shell clears the model and the beam's start. Uses the same near-camera scale as the orb
     * drawing.
     */
    static Vec3 orbAnchor(float radius, Vec3 camPos) {
        float k = orbNearScale(nozzle.subtract(camPos));
        // shell (~1.2 r) plus 4 pixels, enough to clear the guard prongs that reach 2.5 px past the
        // nozzle
        return nozzle.add(barrel.scale(radius * 1.2f * k + 4 * PX));
    }

    /**
     * Shrinks effects that are very close to the camera (a camera-relative point). The nozzle in
     * first person is well under a block from your eye, where a few pixels of beam would fill a big
     * part of the screen.
     */
    static float nearScale(Vec3 camRel) {
        return (float) Math.max(0.2, Math.min(1.0, camRel.length() / 2.5));
    }

    /** A gentler version for the orb, which should stay readable while charging in first person. */
    static float orbNearScale(Vec3 camRel) {
        return (float) Math.max(0.3, Math.min(1.0, camRel.length() / 2.0));
    }
}
