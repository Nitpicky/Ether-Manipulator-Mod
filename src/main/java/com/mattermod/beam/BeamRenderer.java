package com.mattermod.beam;

import com.mattermod.item.MatterManipulatorItem;
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
        if (p != null && p.getMainHandItem().getItem() instanceof MatterManipulatorItem)
            event.setCanceled(true);
    }

    /**
     * Frame handling for every effect (see {@link com.mattermod.client.Fx}): start of frame, then
     * right after entities the world's effects are produced and everything recorded this frame is
     * drawn at once.
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        // Shader packs fire these again for the shadow map, from the sun's view: drawing
        // there made a second beam come out of the sky (see ShaderCompat)
        if (com.mattermod.client.ShaderCompat.inShadowPass()) return;
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            com.mattermod.client.Fx.beginFrame();
        } else if (stage == com.mattermod.client.Fx.STAGE) {
            com.mattermod.client.Fx.drawFrame(() -> produceWorldFx(event));
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
        if (held.getItem() instanceof MatterManipulatorItem) {
            renderLocal(mc, player, held, camPos, pt, time, dt, ps, buf, m);
            BlockGlow.render(mc, player, held, camPos, time, pt, buf, m);
            OreSense.render(mc, player, held, camPos, time, buf, m);
        } else if (lastBeamState) {
            lastBeamState = false;
            BeamRenderState.clearBeam();
        }

        VertexConsumer vc = com.mattermod.client.Fx.glow();
        renderRemote(mc, camPos, pt, time, dt, proj, view, vc, m);

        BeaconMarkers.render(mc, player, camPos, camLeft, time, buf, m);
        ModuleMarkers.render(mc, camPos, camLeft, time, buf, m);
        LeftClickFx.render(mc, camPos, pt, time, vc, m);
        if (held.getItem() instanceof MatterManipulatorItem)
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
        float[] color = com.mattermod.item.ManipulatorColors.beamRgba(held);

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
                            player, com.mattermod.trait.Traits.beamRange(held), pt);
            Vec3 rawHit = hit.getLocation();
            boolean hitSomething = hit.getType() != HitResult.Type.MISS;
            float extend = Math.min(1f, (BeamRenderState.beamTicks + pt) / 2.5f);
            int level = BeamRenderState.combatLevel;
            // The end point follows the aim exactly; the curve's bend trails behind
            // and catches up, so a sweep bends the beam like something elastic
            Vec3 aimNow = player.getViewVector(pt);
            localAim =
                    (!lastBeamState || localAim == null)
                            ? aimNow
                            : elasticDir(localAim, aimNow, dt);
            localTip = rawHit;
            strikeAge = strikeAge(player.getId(), pt);

            drawBeam(
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
            drawArcs(mc, camPos, player, hit, time, pt, color);
            strikeAge = 99f;
            drawRefraction(m, camPos, player, held, level, time, color, extend);
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
            if (!(held.getItem() instanceof MatterManipulatorItem)) continue;

            float[] color = com.mattermod.item.ManipulatorColors.beamRgba(held);
            HitResult hit =
                    BeamTrace.castWithEntities(p, com.mattermod.trait.Traits.beamRange(held), pt);
            Vec3 toW = hit.getLocation();
            remoteTips.put(p.getId(), toW);
            Vec3 aimNow = p.getViewVector(pt);
            Vec3 prevAim = remoteAims.get(p.getId());
            Vec3 aim = prevAim == null ? aimNow : elasticDir(prevAim, aimNow, dt);
            remoteAims.put(p.getId(), aim);
            float heldTicks = gameTime - e.getValue() + pt;
            int lvl = RemoteBeams.level(p.getId());
            strikeAge = strikeAge(p.getId(), pt);
            Vec3[] nb = Muzzle.resolve(p.getId(), false, proj, view, camPos);
            Vec3 fromW = nb != null ? nb[0] : approximateMuzzle(p, pt);
            boolean hitSomething = hit.getType() != HitResult.Type.MISS;
            drawBeam(
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
            drawArcs(mc, camPos, p, hit, time, pt, color);
            strikeAge = 99f;
            drawRefraction(m, camPos, p, held, lvl, time, color, Math.min(1f, heldTicks / 2.5f));
            spawnImpactFx(level, hit, toW, fromW, color, lvl, dt, hitSomething, p.getId());
        }
    }

    /** Ticks since this player's beam last struck (99 when long ago), for the strike flash. */
    private static float strikeAge = 99f;

    private static float strikeAge(int id, float pt) {
        RemoteBeams.Pulse pu = RemoteBeams.pulseOf(id);
        if (pu == null || pu.strikeAt == Long.MIN_VALUE) return 99f;
        return Math.min(99f, RemoteBeams.clientTime() - pu.strikeAt + pt);
    }

    /** Seeds whose arc sparks were already thrown, per player. */
    private static final Map<Integer, Integer> arcSparked = new java.util.HashMap<>();

    /**
     * Combat: Arc. Each strike the server picks the creatures it jumps to (more with pressure); here
     * each jump is a jagged, pixel-wide bolt from the creature in the beam that flashes in the beam's
     * colour and dies out over a few ticks, with a pop of sparks where it lands.
     */
    private static void drawArcs(
            Minecraft mc, Vec3 camPos, Player player, HitResult hit, float time, float pt, float[] color) {
        RemoteBeams.Pulse pu = RemoteBeams.pulseOf(player.getId());
        if (pu == null || pu.arcs.isEmpty() || !(hit instanceof EntityHitResult eh)) return;
        float age = strikeAge(player.getId(), pt);
        if (age > 5f) return;
        float life = 1f - age / 5f;
        Vec3 from = eh.getEntity().getBoundingBox().getCenter();
        boolean spark = !Integer.valueOf(pu.seed).equals(arcSparked.put(player.getId(), pu.seed));
        int k = 0;
        for (int id : pu.arcs) {
            Entity e = mc.level.getEntity(id);
            if (e == null) continue;
            Vec3 to = e.getBoundingBox().getCenter();
            bolt(camPos, from, to, pu.seed * 31 + k++, life, color);
            if (spark)
                for (int i = 0; i < 5; i++)
                    mc.level.addParticle(
                            ParticleTypes.ELECTRIC_SPARK,
                            to.x,
                            to.y,
                            to.z,
                            (Math.random() - .5) * .3,
                            Math.random() * .2,
                            (Math.random() - .5) * .3);
        }
    }

    /** A jagged lightning line between two points: straight segments, kinks every half block. */
    private static void bolt(Vec3 camPos, Vec3 fromW, Vec3 toW, int seed, float life, float[] color) {
        Vec3 a = fromW.subtract(camPos), b = toW.subtract(camPos);
        Vec3 d = b.subtract(a);
        double len = d.length();
        if (len < 0.1) return;
        Vec3 axis = d.scale(1 / len);
        Vec3 u = axis.cross(new Vec3(0, 1, 0));
        if (u.lengthSqr() < 1e-6) u = axis.cross(new Vec3(1, 0, 0));
        u = u.normalize();
        Vec3 v = u.cross(axis).normalize();
        int n = Math.max(3, (int) Math.round(len / 0.5));
        java.util.Random r = new java.util.Random(seed);
        Vec3[] pts = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            double t = (double) i / n;
            double j = (i == 0 || i == n) ? 0 : 0.28 * Math.sin(Math.PI * t);
            pts[i] = a.add(d.scale(t))
                    .add(u.scale((r.nextDouble() - .5) * 2 * j))
                    .add(v.scale((r.nextDouble() - .5) * 2 * j));
        }
        float core = 0.5f * PX, glow = 1.8f * PX;
        float[] col = {mix(color[0], 1f, 0.3f), mix(color[1], 1f, 0.3f), mix(color[2], 1f, 0.3f), life};
        for (int i = 0; i < n; i++) {
            Vec3 p0 = pts[i], p1 = pts[i + 1];
            Vec3 tan = p1.subtract(p0).normalize();
            Vec3 s0 = tan.cross(p0.scale(-1)).normalize(), s1 = tan.cross(p1.scale(-1)).normalize();
            float k0 = nearScale(p0), k1 = nearScale(p1);
            lane(p0.add(s0.scale(-glow * k0)), p1.add(s1.scale(-glow * k1)),
                    p0.add(s0.scale(-core * k0)), p1.add(s1.scale(-core * k1)), col, col, 0f, 0.5f);
            lane(p0.add(s0.scale(-core * k0)), p1.add(s1.scale(-core * k1)),
                    p0.add(s0.scale(core * k0)), p1.add(s1.scale(core * k1)), col, col, -1f, -1f);
            lane(p0.add(s0.scale(core * k0)), p1.add(s1.scale(core * k1)),
                    p0.add(s0.scale(glow * k0)), p1.add(s1.scale(glow * k1)), col, col, 0.5f, 0f);
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
        double range = com.mattermod.trait.Traits.beamRange(held);
        int k = 0;
        for (Vec3 dir :
                BeamCastHelper.refractRays(
                        look, com.mattermod.trait.Traits.rank(held, "photonic.refraction"))) {
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
        int lv = Math.max(0, Math.min(3, level - 1)); // pressure 1-4
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
        // Pressure: a calm, thin line at 1 that thickens, shakes and runs hotter up to 4
        float amp = (1.2f + 0.7f * lv) * PX; // a clear sway, the same size all along the beam
        float speed = 1f + 0.6f * lv;
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

        float core = (0.4f + 0.25f * lv) * PX;
        float glow = (1.8f + 0.8f * lv) * PX;
        // Each strike sends a bright band from the nozzle to the target and flares the glow
        float age = strikeAge;
        float flare = age < 4f ? 1f - age / 4f : 0f;
        double head = age / 2.5;
        float heat = 0.15f * lv; // the colour runs whiter as pressure builds
        Vec3 prevL0 = null, prevL1 = null, prevR1 = null, prevR0 = null;
        float[] prevCol = null;
        for (int i = 0; i <= n; i++) {
            Vec3 p = pts[i];
            Vec3 tan = pts[Math.min(n, i + 1)].subtract(pts[Math.max(0, i - 1)]).normalize();
            Vec3 side = tan.cross(p.scale(-1)); // across the line, facing the camera
            if (side.lengthSqr() < 1e-9) side = su;
            side = side.normalize();
            float ks = nearScale(p);
            double t = (double) i / n;
            // hue gradient along the beam, flowing outward
            float hue = hsv[0] + 0.025f * (float) Math.sin((t * 1.5 - time * 0.05) * Math.PI * 2);
            float[] rgb = hsvToRgb(hue, hsv[1], hsv[2]);
            float fadeIn = (float) Math.min(1, t * n / (1.5 * sub)); // soft start at the nozzle
            if (!hitSomething) {
                // hitting nothing: the end dissolves over the last stretch, the fade's edge
                // breathing a little
                double fadeLen =
                        Math.min(0.45, 2.5 / Math.max(full, 0.1))
                                * (0.85 + 0.15 * Math.sin(time * 0.35));
                double into = (t - (1 - fadeLen)) / fadeLen;
                if (into > 0) fadeIn *= (float) Math.pow(1 - Math.min(1, into), 1.6);
            }
            double off = (t - head) / 0.12;
            float band = head <= 1.2 ? (float) Math.exp(-off * off) : 0f;
            float w = Math.min(1f, heat + 0.6f * band);
            float[] col = {
                mix(rgb[0], 1f, w), mix(rgb[1], 1f, w), mix(rgb[2], 1f, w),
                Math.min(1f, fadeIn * (0.85f + 0.15f * lv / 3f + 0.35f * flare))
            };
            float gw = glow * (1f + 0.35f * flare + 0.5f * band), cw = core * (1f + 0.6f * band);
            Vec3 l0 = p.add(side.scale(-gw * ks)), l1 = p.add(side.scale(-cw * ks));
            Vec3 r1 = p.add(side.scale(cw * ks)), r0 = p.add(side.scale(gw * ks));
            if (prevL0 != null) {
                // outer glow: fades from the core edge to nothing; centre: bright, whiter
                lane(prevL0, l0, prevL1, l1, prevCol, col, 0f, 0.45f);
                lane(prevL1, l1, prevR1, r1, prevCol, col, -1f, -1f);
                lane(prevR1, r1, prevR0, r0, prevCol, col, 0.45f, 0f);
            }
            prevL0 = l0;
            prevL1 = l1;
            prevR1 = r1;
            prevR0 = r0;
            prevCol = col;
        }
    }

    /**
     * One lane of the ribbon between two cross-sections. {@code ea, eb} are the alpha of the lane's
     * two long edges; -1 means the bright core.
     */
    private static void lane(
            Vec3 a0, Vec3 a1, Vec3 b0, Vec3 b1, float[] c0, float[] c1, float ea, float eb) {
        com.mattermod.client.Fx.beamQuad(
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
