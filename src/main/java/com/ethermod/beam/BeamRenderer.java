package com.ethermod.beam;

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

    private static final List<Spark> sparks = new ArrayList<>();
    private static final Random RNG = new Random();

    private static long lastNanos = System.nanoTime();
    private static boolean lastBeamState = false;
    private static Vec3 lastFromW = Vec3.ZERO, lastToW = Vec3.ZERO;
    private static float spawnDebt = 0f;

    private static Vec3 localTip, localAim;
    private static final Map<Integer, Vec3> remoteTips = new HashMap<>();
    private static final Map<Integer, Vec3> remoteAims = new HashMap<>();

    /** Drops sparks, crown spikes and remote-beam state when the client changes world or server. */
    public static void clearCaches() {
        sparks.clear();
        spikes.clear();
        remoteTips.clear();
        remoteAims.clear();
    }

    /** How far the beam's bend trails the aim: the time to close half the gap. */
    private static final double BEND_HALF_LIFE = 0.06;

    private static Vec3 elasticDir(Vec3 from, Vec3 to, float dt) {
        double k = 1 - Math.pow(0.5, dt / BEND_HALF_LIFE);
        Vec3 v = from.add(to.subtract(from).scale(k));
        return v.lengthSqr() < 1e-9 ? to : v.normalize();
    }

    /** The local player's nozzle this frame (world) and barrel direction; null if unknown. */
    public static Vec3 nozzle, barrel;

    /**
     * With the Manipulator out, the beam's own target preview replaces the vanilla block outline,
     * which would box foliage the beam passes through.
     */
    @SubscribeEvent
    public static void onBlockHighlight(
            net.neoforged.neoforge.client.event.RenderHighlightEvent.Block event) {
        Player p = Minecraft.getInstance().player;
        if (p != null && p.getMainHandItem().getItem() instanceof EtherManipulatorItem)
            event.setCanceled(true);
    }

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
            nozzle = approximateMuzzle(player, pt);
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

        BeaconMarkers.render(mc, player, camPos, camLeft, time, buf, m);
        ModuleMarkers.render(mc, camPos, camLeft, time, buf, m);
        LeftClickFx.render(mc, camPos, pt, time, vc, m);
        if (held.getItem() instanceof EtherManipulatorItem)
            MassPreview.render(mc, player, held, camPos, time, vc, m);

        updateAndRenderSparks(dt, camPos, vc, m);
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
                drawBeam(
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
                drawArcs(m, camPos, player, held, hit, level, time, color);
                drawRefraction(m, camPos, player, held, level, time, color, extend);
            }
            spawnImpactFx(mc.level, hit, localTip, nozzle, color, level, dt, hitSomething, -1);
            Vec3 toW = localTip;

            lastFromW = nozzle;
            lastToW = toW;
            lastBeamState = true;
        } else if (lastBeamState) {
            spawnShutdown(lastFromW, lastToW, color);
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
            Vec3 fromW = nb != null ? nb[0] : approximateMuzzle(p, pt);
            boolean hitSomething = hit.getType() != HitResult.Type.MISS;
            if (com.ethermod.item.OrbMode.get(held) == com.ethermod.item.OrbMode.LIGHT) {
                lightBeacon(mc, mc.renderBuffers().bufferSource(), m, camPos, fromW, toW, Math.min(1f, heldTicks / 2.5f), time, color);
                spawnImpactFx(level, hit, toW, fromW, color, lvl, dt, hitSomething, p.getId());
                continue;
            }
            drawBeam(
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
            drawArcs(m, camPos, p, held, hit, lvl, time, color);
            drawRefraction(m, camPos, p, held, lvl, time, color, Math.min(1f, heldTicks / 2.5f));
            spawnImpactFx(level, hit, toW, fromW, color, lvl, dt, hitSomething, p.getId());
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

    /**
     * Combat: Arc. From ramp level 2 the beam jumps off the target to the nearest creatures (one
     * per rank), mirroring the server's pick.
     */
    private static void drawArcs(
            Matrix4f m,
            Vec3 camPos,
            Player player,
            ItemStack held,
            HitResult hit,
            int level,
            float time,
            float[] color) {
        if (!(hit instanceof EntityHitResult eh) || level < 2) return;
        int arc = com.ethermod.trait.Traits.rank(held, "plasmic.arc");
        if (arc <= 0 || !(eh.getEntity() instanceof net.minecraft.world.entity.LivingEntity first))
            return;
        Vec3 from = first.getBoundingBox().getCenter();
        List<net.minecraft.world.entity.LivingEntity> near =
                player.level()
                        .getEntitiesOfClass(
                                net.minecraft.world.entity.LivingEntity.class,
                                first.getBoundingBox().inflate(4 + arc),
                                e ->
                                        e != first
                                                && !com.ethermod.beam.Mounts.own(player, e)
                                                && e.isAlive()
                                                && e.isPickable()
                                                && !(e instanceof Player));
        near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(first)));
        for (int i = 0; i < Math.min(arc, near.size()); i++) {
            Vec3 to = near.get(i).getBoundingBox().getCenter();
            Vec3 dir = to.subtract(from).normalize();
            drawBeam(
                    -(player.getId() * 32 + i + 1), // own roll/arcs, no swell (not the striking beam)
                    m,
                    camPos,
                    from,
                    dir,
                    to,
                    1f,
                    Math.max(1, level - 1),
                    time + i * 7,
                    color,
                    true);
        }
    }

    /** Photonic: Refraction, extra rays fanning out behind the glass the beam went through. */
    private static void drawRefraction(
            Matrix4f m,
            Vec3 camPos,
            Player player,
            ItemStack held,
            int level,
            float time,
            float[] color,
            float extend) {
        Vec3 from = BeamTrace.lastRefract;
        if (from == null) return;
        Vec3 look = player.getLookAngle();
        double range = com.ethermod.trait.Traits.beamRange(held);
        int k = 0;
        for (Vec3 dir :
                BeamCastHelper.refractRays(
                        look, com.ethermod.trait.Traits.rank(held, "photonic.refraction"))) {
            var hit =
                    player.level()
                            .clip(
                                    new net.minecraft.world.level.ClipContext(
                                            from,
                                            from.add(dir.scale(range)),
                                            net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                            net.minecraft.world.level.ClipContext.Fluid.NONE,
                                            player));
            drawBeam(
                    -(player.getId() * 32 + 16 + k),
                    m,
                    camPos,
                    from,
                    dir,
                    hit.getLocation(),
                    extend,
                    Math.max(1, level - 1),
                    time + 11 * ++k,
                    color,
                    hit.getType() != HitResult.Type.MISS);
        }
    }

    static Vec3 approximateMuzzle(Player p, float pt) {
        Vec3 eye = p.getEyePosition(pt);
        Vec3 look = p.getViewVector(pt);
        Vec3 right =
                Math.abs(look.y) < 0.999
                        ? look.cross(new Vec3(0, 1, 0)).normalize()
                        : new Vec3(1, 0, 0);
        float hs = p.getMainArm() == HumanoidArm.RIGHT ? 1f : -1f;
        return eye.add(right.scale(0.35f * hs)).add(look.scale(0.55f)).add(0, -0.3, 0);
    }

    // =========================================================================
    // The beam itself
    // =========================================================================

    /**
     * The beam: one continuous line from the nozzle that bends towards the (lagging) tip. It runs
     * through a row of points that each drift on their own slow, smooth wobble, so the line ripples
     * without jumping. It is a single flat ribbon facing the camera with a bright centre that fades
     * to nothing at its edges, so the glow never overlaps itself. The colour shifts hue along its
     * length, and the gradient flows towards the target.
     */
    private static void drawBeam(
            int owner,
            Matrix4f m,
            Vec3 camPos,
            Vec3 fromW,
            Vec3 aim,
            Vec3 toW,
            float extend,
            int level,
            float time,
            float[] color,
            boolean hitSomething) {
        double full = fromW.distanceTo(toW);
        if (full < 0.05) return;
        Vec3 a = fromW.subtract(camPos);
        Vec3 tip = toW.subtract(camPos);
        Vec3 c =
                a.add(
                        aim.scale(
                                full
                                        * 0.5)); // control point on the aim line: a lagging tip
                                                 // bends the beam
        int lv = Math.max(0, Math.min(2, level - 1)); // 0, 1, 2
        float[] hsv = rgbToHsv(color[0], color[1], color[2]);

        // A few control points along the curve, each drifting slowly on its own
        int nc = Math.max(3, Math.min(24, (int) Math.round(full / 0.9)));
        Vec3[] ctl = new Vec3[nc + 1];
        for (int i = 0; i <= nc; i++) {
            double t = (double) i / nc * extend;
            double u = 1 - t;
            ctl[i] = a.scale(u * u).add(c.scale(2 * u * t)).add(tip.scale(t * t));
        }
        Vec3 axis = ctl[nc].subtract(ctl[0]).normalize();
        Vec3 su = axis.cross(new Vec3(0, 1, 0));
        if (su.lengthSqr() < 1e-6) su = axis.cross(new Vec3(1, 0, 0));
        su = su.normalize();
        Vec3 sv = su.cross(axis).normalize();
        float amp = (1.6f + 0.6f * lv) * PX; // a clear sway, the same size all along the beam
        float speed = 1f + 0.4f * lv;
        for (int i = 1; i < nc; i++) {
            long h = i * 0x9E3779B97F4A7C15L;
            double f1 = 0.08 + ((h >>> 8) & 0xff) / 255.0 * 0.08,
                    f2 = 0.06 + ((h >>> 16) & 0xff) / 255.0 * 0.07;
            double p1 = ((h >>> 24) & 0xff) / 255.0 * 6.283,
                    p2 = ((h >>> 32) & 0xff) / 255.0 * 6.283;
            double ox = Math.sin(time * f1 * speed + p1), oy = Math.sin(time * f2 * speed + p2);
            float ks = nearScale(ctl[i]);
            ctl[i] = ctl[i].add(su.scale(ox * amp * ks)).add(sv.scale(oy * amp * ks));
        }
        // Smooth curve through them (Catmull-Rom), so there are no corners to fold over
        int sub = 6;
        int n = nc * sub;
        Vec3[] pts = new Vec3[n + 1];
        for (int i = 0; i < nc; i++) {
            Vec3 p0 = ctl[Math.max(0, i - 1)],
                    p1 = ctl[i],
                    p2 = ctl[i + 1],
                    p3 = ctl[Math.min(nc, i + 2)];
            for (int j = 0; j < sub; j++) {
                double t = (double) j / sub, t2 = t * t, t3 = t2 * t;
                pts[i * sub + j] =
                        p1.scale(2)
                                .add(p2.subtract(p0).scale(t))
                                .add(
                                        p0.scale(2)
                                                .subtract(p1.scale(5))
                                                .add(p2.scale(4))
                                                .subtract(p3)
                                                .scale(t2))
                                .add(
                                        p1.scale(3)
                                                .subtract(p0)
                                                .subtract(p2.scale(3))
                                                .add(p3)
                                                .scale(t3))
                                .scale(0.5);
            }
        }
        pts[n] = ctl[nc];

        // the look chosen on the FX bench: the Prism rope, with surface arcs and the damage swell
        PrismRope.draw(owner, pts, sub, su, time, level, color, hitSomething, full);
    }

    /**
     * One lane of the ribbon between two cross-sections. {@code ea, eb} are the alpha of the lane's
     * two long edges; -1 means the bright core.
     */
    private static void lane(
            Vec3 a0, Vec3 a1, Vec3 b0, Vec3 b1, float[] c0, float[] c1, float ea, float eb) {
        com.ethermod.client.Fx.beamQuad(
                a0, laneCol(c0, ea), a1, laneCol(c1, ea), b1, laneCol(c1, eb), b0, laneCol(c0, eb));
    }

    private static int[] laneCol(float[] c, float edge) {
        if (edge < 0) {
            return new int[] {
                BeamFx.ch(mix(c[0], 1f, 0.25f)),
                BeamFx.ch(mix(c[1], 1f, 0.25f)),
                BeamFx.ch(mix(c[2], 1f, 0.25f)),
                BeamFx.ch(0.95f * c[3])
            };
        }
        return new int[] {
            BeamFx.ch(c[0]), BeamFx.ch(c[1]), BeamFx.ch(c[2]), BeamFx.ch(edge * c[3])
        };
    }

    private static float[] rgbToHsv(float r, float g, float b) {
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0;
        if (d > 1e-6f) {
            if (max == r) h = ((g - b) / d) % 6f;
            else if (max == g) h = (b - r) / d + 2f;
            else h = (r - g) / d + 4f;
            h /= 6f;
            if (h < 0) h += 1f;
        }
        return new float[] {h, max <= 0 ? 0 : d / max, max};
    }

    private static float[] hsvToRgb(float h, float s, float v) {
        int rgb = net.minecraft.util.Mth.hsvToRgb(h - (float) Math.floor(h), s, v);
        return new float[] {
            ((rgb >> 16) & 255) / 255f, ((rgb >> 8) & 255) / 255f, (rgb & 255) / 255f
        };
    }

    // =========================================================================
    // Impact: the crown, and popping sparks
    // =========================================================================

    private static final List<Spike> spikes = new ArrayList<>();
    private static float crownDebt = 0f;

    private static void spawnImpactFx(
            ClientLevel level,
            HitResult hit,
            Vec3 toW,
            Vec3 fromW,
            float[] color,
            int combatLevel,
            float dt,
            boolean hitSomething,
            int owner) {
        Vec3 back = fromW.subtract(toW).normalize();

        // Crown: thin spikes flaring back from the impact in a ring, a few at a time.
        // Nothing hit: no crown, the beam just fades out (see drawBeam)
        crownDebt += dt * (hitSomething ? 20f + 5f * combatLevel : 0f);
        while (crownDebt >= 1f) {
            crownDebt -= 1f;
            spikes.add(Spike.crown(owner, toW, back, color, hitSomething, combatLevel));
        }

        if (!hitSomething) return;
        // Sparks pop off in small bursts rather than a constant trickle
        spawnDebt += dt * (6f + 2f * combatLevel);
        while (spawnDebt >= 1f) {
            spawnDebt -= 1f;
            int burst = 3 + RNG.nextInt(3);
            for (int i = 0; i < burst; i++) {
                Vec3 dir = back.add(rand(1.6), rand(1.6) + 0.35, rand(1.6)).normalize();
                sparks.add(
                        new Spark(
                                toW.add(back.scale(0.03)),
                                dir.scale(4.0 + RNG.nextDouble() * 4.0),
                                0.18f + RNG.nextFloat() * 0.22f,
                                color));
            }
        }

        if (RNG.nextFloat() < dt * 12f) {
            if (hit instanceof BlockHitResult bh) {
                BlockPos pos = bh.getBlockPos();
                BlockState state = level.getBlockState(pos);
                if (!state.isAir()) {
                    level.addParticle(
                            new BlockParticleOption(ParticleTypes.BLOCK, state),
                            toW.x,
                            toW.y,
                            toW.z,
                            back.x * 0.6 + rand(0.3),
                            back.y * 0.6 + 0.2,
                            back.z * 0.6 + rand(0.3));
                }
            } else {
                level.addParticle(
                        ParticleTypes.ELECTRIC_SPARK,
                        toW.x,
                        toW.y,
                        toW.z,
                        rand(0.4),
                        rand(0.4) + 0.1,
                        rand(0.4));
            }
        }
    }

    /** The beam breaks into drifting pixels when released. */
    private static void spawnShutdown(Vec3 fromW, Vec3 toW, float[] color) {
        Vec3 diff = toW.subtract(fromW);
        int count = 16 + RNG.nextInt(10);
        for (int i = 0; i < count; i++) {
            Vec3 pos = fromW.add(diff.scale(RNG.nextDouble()));
            Vec3 vel =
                    new Vec3(rand(1), rand(1), rand(1))
                            .normalize()
                            .scale(0.6 + RNG.nextDouble() * 0.8);
            sparks.add(new Spark(pos, vel, 0.3f + RNG.nextFloat() * 0.3f, color).floaty());
        }
    }

    private static void updateAndRenderSparks(
            float dt, Vec3 camPos, VertexConsumer vc, Matrix4f m) {
        if (sparks.size() > 400) sparks.subList(0, sparks.size() - 400).clear();
        Iterator<Spark> it = sparks.iterator();
        while (it.hasNext()) {
            Spark s = it.next();
            if (!s.update(dt)) {
                it.remove();
                continue;
            }
            float life = 1f - s.age / s.lifetime;
            // pop: a white flash at birth, then shrink to nothing
            float flash = Math.max(0f, 1f - s.age / 0.05f);
            float h = PX * 0.5f * (0.35f + 0.65f * life) * nearScale(s.pos.subtract(camPos));
            BeamFx.cube(
                    vc,
                    m,
                    s.pos.subtract(camPos),
                    h,
                    mix(s.r, 1f, 0.4f + 0.6f * flash),
                    mix(s.g, 1f, 0.4f + 0.6f * flash),
                    mix(s.b, 1f, 0.4f + 0.6f * flash),
                    0.95f);
        }

        if (spikes.size() > 200) spikes.subList(0, spikes.size() - 200).clear();
        Iterator<Spike> sp = spikes.iterator();
        while (sp.hasNext()) {
            Spike k = sp.next();
            k.age += dt;
            if (k.age >= k.lifetime) {
                sp.remove();
                continue;
            }
            float t = k.age / k.lifetime;
            float grow = 1f - (1f - t) * (1f - t); // ease out
            float alpha = 1f - t * t;
            // stays on the beam's tip as it moves, instead of where it was spawned
            Vec3 anchor =
                    k.owner == -1 ? (lastBeamState ? localTip : null) : remoteTips.get(k.owner);
            Vec3 o = (anchor != null ? anchor : k.origin).subtract(camPos);
            float ks = nearScale(o);
            Vec3 end = o.add(k.dir.scale(k.length * grow * ks));
            // tapered: a thick base pixel and a thinner tip
            Vec3 mid = o.add(end.subtract(o).scale(0.4));
            BeamFx.prism(vc, m, o, mid, PX * 0.6f * ks, 0.785, k.r, k.g, k.b, alpha);
            BeamFx.prism(
                    vc,
                    m,
                    mid,
                    end,
                    PX * 0.3f * ks,
                    0.785,
                    mix(k.r, 1f, 0.5f),
                    mix(k.g, 1f, 0.5f),
                    mix(k.b, 1f, 0.5f),
                    alpha);
        }
    }

    private static final class Spark {
        Vec3 pos, vel;
        final float lifetime, r, g, b;
        float age;
        boolean floaty;

        Spark(Vec3 pos, Vec3 vel, float lifetime, float[] c) {
            this.pos = pos;
            this.vel = vel;
            this.lifetime = lifetime;
            this.r = c[0];
            this.g = c[1];
            this.b = c[2];
        }

        Spark floaty() {
            floaty = true;
            return this;
        }

        boolean update(float dt) {
            age += dt;
            if (age >= lifetime) return false;
            if (floaty) vel = vel.scale(Math.pow(0.05, dt));
            // fast burst, heavy drag, then gravity takes over
            else vel = vel.add(0, -14.0 * dt, 0).scale(Math.pow(0.02, dt));
            pos = pos.add(vel.scale(dt));
            return true;
        }
    }

    /** One spike of the impact crown. */
    private static final class Spike {
        final Vec3 origin, dir;
        final float length, lifetime, r, g, b;
        final int owner;
        float age;
        private static double crownSpin;

        private Spike(int owner, Vec3 origin, Vec3 dir, float length, float lifetime, float[] c) {
            this.owner = owner;
            this.origin = origin;
            this.dir = dir;
            this.length = length;
            this.lifetime = lifetime;
            this.r = mix(c[0], 1f, 0.3f);
            this.g = mix(c[1], 1f, 0.3f);
            this.b = mix(c[2], 1f, 0.3f);
        }

        /**
         * Spikes sit on a cone around the way back to the nozzle, at slowly turning ring positions.
         */
        static Spike crown(int owner, Vec3 at, Vec3 back, float[] c, boolean hit, int level) {
            Vec3 u = back.cross(new Vec3(0, 1, 0));
            if (u.lengthSqr() < 1e-6) u = back.cross(new Vec3(1, 0, 0));
            u = u.normalize();
            Vec3 v = u.cross(back).normalize();
            int points = 6;
            crownSpin += 0.37;
            double ang =
                    Math.round(crownSpin * points / (Math.PI * 2)) * (Math.PI * 2 / points)
                            + (RNG.nextDouble() - 0.5) * 0.35;
            double spread = Math.toRadians(16 + RNG.nextDouble() * 10);
            Vec3 ring = u.scale(Math.cos(ang)).add(v.scale(Math.sin(ang)));
            Vec3 dir = back.scale(Math.cos(spread)).add(ring.scale(Math.sin(spread))).normalize();
            float len = 0.22f + RNG.nextFloat() * 0.16f + 0.05f * level;
            return new Spike(owner, at, dir, len, 0.14f + RNG.nextFloat() * 0.1f, c);
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

    private static double rand(double s) {
        return (RNG.nextDouble() - 0.5) * s;
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
