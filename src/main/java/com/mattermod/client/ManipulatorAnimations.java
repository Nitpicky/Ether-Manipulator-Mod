package com.mattermod.client;

import com.mattermod.beam.BeamBallEntity;
import com.mattermod.beam.BeamRenderState;
import com.mattermod.beam.RemoteBeams;
import com.mattermod.item.MatterManipulatorItem;
import com.mattermod.item.OrbMode;
import com.mattermod.trait.Traits;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.EasingType;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.constant.DataTickets;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Client-only: picks the Manipulator's keyframed animation from what its holder is doing. Loop and
 * hold behaviour come from the animation file. Only referenced from {@link
 * MatterManipulatorItem#registerControllers} behind a client-dist check.
 *
 * <p>One controller, "main", drives the prongs and ring. The ring itself is physical, applied in
 * {@link ManipulatorItemModel#setCustomAnimations}: the keyframes and the Energy charge's motor
 * drive it, and its inertia ({@link #ring}) gathers, overshoots and settles every turn. Body
 * reactions (hurt, falls) are left to vanilla.
 *
 * <p>Animation files: model-v3/anims_v4.py in the project files generates them.
 */
public final class ManipulatorAnimations {

    private ManipulatorAnimations() {}

    private static final String PREFIX = "animation.matter_manipulator.";

    private static RawAnimation anim(String name) {
        return RawAnimation.begin().thenPlay(PREFIX + name);
    }

    private static final RawAnimation IDLE = anim("idle");
    private static final RawAnimation FIDGET = anim("fidget");
    private static final RawAnimation SUMMON = anim("summon");
    private static final RawAnimation UNSUMMON = anim("unsummon");
    private static final RawAnimation MODE_SWITCH = anim("mode_switch");
    private static final RawAnimation INSPECT = anim("inspect");
    private static final RawAnimation BEAM_START = anim("beam_start");
    private static final RawAnimation[] MINE = {
        anim("mine"), anim("mine_2"), anim("mine_3"), anim("mine_4")
    };
    private static final RawAnimation MINE_END = anim("mine_end");
    private static final RawAnimation TILL = anim("till");
    private static final RawAnimation OVERCHARGE = anim("overcharge");
    private static final RawAnimation TETHER = anim("tether");
    private static final RawAnimation TETHER_GRIP = anim("tether_grip");
    private static final RawAnimation TETHER_RELEASE = anim("tether_release");
    private static final RawAnimation PULL = anim("pull");
    private static final RawAnimation GRENADE = anim("grenade");
    private static final RawAnimation BEACON = anim("beacon");
    /** Botanic left click: a sickle sweep over the crops. */
    private static final RawAnimation HARVEST = anim("harvest");
    /** Signal click per strength band (1-4, 5-8, 9-12, 13-15), and the hold-to-ramp loop. */
    private static final RawAnimation[] CYCLE = {
        anim("cycle_1"), anim("cycle_2"), anim("cycle_3"), anim("cycle_4")
    };

    private static final RawAnimation CYCLE_RAMP = anim("cycle_ramp");
    // pollen drift: the push that sends it off, then the guide pose while it flies
    private static final RawAnimation GROWTH_GUIDE =
            RawAnimation.begin()
                    .thenPlay(PREFIX + "growth_release")
                    .thenLoop(PREFIX + "growth");
    private static final RawAnimation GROWTH_END = anim("fire_growth");
    private static final RawAnimation MASS_REACH = anim("mass_driver");
    // a quick claw snap onto the first block, then a clamp every 3 ticks while sweeping
    private static final RawAnimation MASS_GRAB =
            RawAnimation.begin()
                    .thenPlay(PREFIX + "mass_grab")
                    .thenLoop(PREFIX + "mass_sweep");
    private static final RawAnimation MASS_GRIP = anim("mass_grip");
    private static final RawAnimation MASS_RELEASE = anim("mass_release");
    private static final RawAnimation MASS_PLACE = anim("mass_place");


    /** Energy charge: each stage opens further; the spin itself comes from {@link #spin}. */
    private static final RawAnimation[] CHARGE_STAGES = new RawAnimation[3];

    static {
        for (int i = 0; i < 3; i++) {
            CHARGE_STAGES[i] =
                    RawAnimation.begin()
                            .thenPlay(PREFIX + "charge_" + (i + 1))
                            .thenLoop(PREFIX + "charge_" + (i + 1) + "_loop");
        }
    }

    /** Ring spin of each Energy charge stage, in degrees per second. */
    private static final float[] STAGE_SPIN = {330f, 540f, 860f};

    private static final Map<OrbMode, RawAnimation> CHARGE = new EnumMap<>(OrbMode.class);
    /** The release after each charged shot; Energy (and anything unlisted) uses "fire". */
    private static final Map<OrbMode, RawAnimation> FIRE_BY = new EnumMap<>(OrbMode.class);

    private static final Map<OrbMode, Float> FIRE_LEN = new EnumMap<>(OrbMode.class);
    private static final RawAnimation FIRE = anim("fire");

    static {
        CHARGE.put(OrbMode.WATER, anim("water"));
        CHARGE.put(OrbMode.LAVA, anim("lava"));
        CHARGE.put(OrbMode.FROST, anim("frost"));
        CHARGE.put(OrbMode.IGNITE, anim("ignite"));
        CHARGE.put(OrbMode.GROWTH, anim("growth_charge"));
        CHARGE.put(OrbMode.LIGHT, anim("light"));
        CHARGE.put(OrbMode.GUST, anim("gust"));

        fire(OrbMode.WATER, "fire_water", 0.65f);
        fire(OrbMode.LAVA, "fire_lava", 0.8f);
        fire(OrbMode.FROST, "fire_frost", 0.42f);
        fire(OrbMode.IGNITE, "fire_ignite", 0.42f);
        fire(OrbMode.LIGHT, "fire_light", 0.48f);
        fire(OrbMode.GUST, "fire_gust", 0.55f);
    }

    private static void fire(OrbMode mode, String name, float len) {
        FIRE_BY.put(mode, anim(name));
        FIRE_LEN.put(mode, len);
    }

    // Lengths of the one-shot animations, in seconds (see the animation file). A one-shot hands
    // over a little before its last frame, which already matches the next pose, so the
    // controller never sits on a finished animation (that's what made summon jump to idle).
    private static final float HANDOVER = 0.05f;
    private static final float SUMMON_LEN = 0.75f;
    private static final float UNSUMMON_LEN = 0.2f;
    private static final float MODE_SWITCH_LEN = 0.48f;
    private static final float FIDGET_LEN = 1.5f;
    private static final float INSPECT_LEN = 2.5f;
    private static final float BEAM_START_LEN = 0.1f;
    private static final float MINE_END_LEN = 0.32f;
    private static final float GROWTH_END_LEN = 0.75f;
    /** After a Growth release, how long the guide pose waits for the drift to show up. */
    private static final float DRIFT_WAIT = 0.4f;
    private static final float FIRE_LEN_ENERGY = 0.6f;
    private static final float TETHER_RELEASE_LEN = 0.45f;
    private static final float PULL_LEN = 0.5f;
    private static final float GRENADE_LEN = 0.55f;
    private static final float HARVEST_LEN = 0.66f;
    private static final float CYCLE_LEN = 0.45f;
    /** Holding the signal click this long means it is ramping (the server steps after 8 ticks). */
    private static final float RAMP_AFTER = 0.4f;
    private static final float MASS_RELEASE_LEN = 0.45f;
    private static final float MASS_PLACE_LEN = 0.3f;

    /** Seconds a full charge is held before the prongs start to strain. */
    private static final float OVERCHARGE_AFTER = 2f;


    /** Per-holder memory to detect "just summoned", "just released" and "just let go". */
    private static final class Track {
        boolean held;
        boolean using;
        float heldSince = -100;
        float releasedAt = -100;
        boolean releaseFires;
        OrbMode releaseMode = OrbMode.ENERGY;
        boolean caught;
        float caughtEndedAt = -100;
        boolean carrying;
        float carryEndedAt = -100;
        boolean drift;
        float driftEndedAt = -100;
        long orbTick = -1;
        boolean orbCaught, orbFlying, flying;
        boolean orbDrift;
        OrbMode lastMode;
        float modeSwitchAt = -100;
        float leftPressAt = -100;
        OrbMode leftPressMode;
        // the left click was a set-down (it came while carrying), not a grab
        boolean leftPressPlaces;
        float inspectAt = -100;
        float putAwayAt = -100;
        boolean beaming;
        boolean beamTill;
        float beamSince = -100;
        float beamEndedAt = -100;
        // a one-shot was triggered again while still playing: restart it
        boolean restart;
        float idleSince = -100;
        float nextFidget = 40 + (float) Math.random() * 80;
        float fidgetAt = -100;
        // the ring's own angle, the last keyframed angle seen, last frame time; skip one frame's
        // movement when a new animation starts
        float ringRaw = Float.NaN;
        float ringAngle;
        float ringTime = -1;
        boolean ringSkip;
        boolean ringWasBlending;
        // the ring's inertia: where the motor drives it, its speed, and how heavy it feels now
        float ringTarget;
        float ringVel;
        float ringHz = 5f;
        float ringZeta = 0.5f;
        // ring spin, integrated per frame
        float spinTarget;
        float spinSpeed;

    }

    private static final Map<Integer, Track> TRACKS = new HashMap<>();

    private static Track track(Player p) {
        return TRACKS.computeIfAbsent(p.getId(), id -> new Track());
    }

    /**
     * Whether the holder should hold the tool up and aimed (the bow pose): while any input is in
     * use, a little after a click or a shot, and while a hook, a carried block or a drift is out.
     */
    public static boolean aiming(Player p) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        Track t = TRACKS.get(p.getId());
        if (t == null) return false;
        float now = now(mc);
        return t.using
                || t.beaming
                || t.carrying
                || t.drift
                || t.orbCaught
                || t.orbFlying
                || now - t.leftPressAt < .7f
                || now - t.releasedAt < .6f;
    }

    private static float now(Minecraft mc) {
        return (mc.level.getGameTime() + mc.getTimer().getGameTimeDeltaPartialTick(false)) / 20f;
    }

    public static AnimationController<MatterManipulatorItem> controller(
            MatterManipulatorItem item) {
        AnimationController<MatterManipulatorItem> c =
                new AnimationController<>(
                        item, "main", BLEND_TICKS, ManipulatorAnimations::predicate);
        // Blends between animations ease out past the target and settle back (an overshoot).
        // Only while blending: inside an animation the keyframes keep their own easing.
        c.setOverrideEasingTypeFunction(
                anim ->
                        c.getAnimationState() == AnimationController.State.TRANSITIONING
                                ? easing(BLEND.getOrDefault(c, BLEND_TICKS))
                                : null);
        return c;
    }

    /**
     * Short cuts (an animation that starts where the last one ended) blend straight; longer ones
     * get the eased overshoot, which needs a few ticks to read rather than pop.
     */
    private static EasingType easing(int ticks) {
        if (ticks <= 2) return EasingType.LINEAR;
        if (ticks == 3) return EasingType.EASE_IN_OUT_SINE;
        return EasingType.EASE_OUT_BACK;
    }

    /** What each controller played last, and the blend chosen into it. */
    private static final Map<AnimationController<?>, RawAnimation> LAST = new WeakHashMap<>();

    private static final Map<AnimationController<?>, Integer> BLEND = new WeakHashMap<>();

    /** Loops whose ring whirls: the ring feels heaviest under them, so it coasts on after. */
    private static final Set<RawAnimation> SPINS = new HashSet<>();

    /** One-shots whose last frame is exactly the idle pose, so idle can follow almost unblended. */
    private static final Set<RawAnimation> ENDS_ON_IDLE = new HashSet<>();

    static {
        ENDS_ON_IDLE.addAll(
                List.of(
                        SUMMON, MODE_SWITCH, FIDGET, INSPECT, FIRE, TETHER_RELEASE, PULL, GRENADE, HARVEST,
                        MASS_PLACE, MINE_END, GROWTH_END));
        ENDS_ON_IDLE.addAll(List.of(CYCLE));
        ENDS_ON_IDLE.addAll(FIRE_BY.values());
        SPINS.addAll(List.of(MINE));
        SPINS.addAll(
                List.of(
                        BEACON,
                        CHARGE.get(OrbMode.GUST),
                        CHARGE.get(OrbMode.LIGHT),
                        CHARGE.get(OrbMode.GROWTH)));
    }

    /**
     * Blend length into {@code to}, in ticks. Chained states (a charge stage into the next, a
     * charge into its release, the ignition into the mining loop) start on the pose the last one
     * ended on, so they cut almost straight and stay in time with the action; states entered from
     * anywhere get a longer eased blend.
     */
    private static int blendFor(RawAnimation from, RawAnimation to) {
        if (to == SUMMON) return 0; // nothing was showing before it
        if (to == CHARGE_STAGES[1] || to == CHARGE_STAGES[2] || to == OVERCHARGE) return 1;
        if (to == FIRE) return 1;
        if (FIRE_BY.containsValue(to)) return 2;
        if (to == TETHER_RELEASE || to == MASS_PLACE || to == MASS_RELEASE) return 2;
        if (to == TETHER_GRIP) return 1; // the claw clamps on the hit
        if (to == TETHER) return 1; // the reach bursts open on the click
        if (to == PULL || to == GRENADE || to == CYCLE_RAMP || to == HARVEST) return 2;
        for (RawAnimation c : CYCLE) if (to == c) return 2;
        if (to == BEAM_START) return 1;
        if (from == BEAM_START && to == MINE[0]) return 1;
        if (to == MINE_END) return 2;
        if (to == MASS_GRAB) return 1;
        if (to == GROWTH_GUIDE) return 1; // the release push starts on the shot
        if (to == GROWTH_END) return 3;
        for (RawAnimation m : MINE) if (to == m) return 4;
        if (to == CHARGE_STAGES[0]) return 3;
        if (to == UNSUMMON) return 1;
        if (to == IDLE) return from != null && ENDS_ON_IDLE.contains(from) ? 1 : 5;
        if (to == MODE_SWITCH || to == INSPECT || to == FIDGET) return 4;
        return BLEND_TICKS;
    }

    /** Default blend between animations, in ticks: long enough for the overshoot to read. */
    private static final int BLEND_TICKS = 6;

    private static PlayState predicate(AnimationState<MatterManipulatorItem> state) {
        ItemStack stack = state.getData(DataTickets.ITEMSTACK);
        // only a tool in a hand follows its holder: an icon drawn meanwhile (creative tab, slot, frame) must not
        // consume the holder's restarts and ring state, or both spin erratically
        var ctx = state.getData(DataTickets.ITEM_RENDER_PERSPECTIVE);
        boolean inHand = ctx == null || ctx.firstPerson() || ctx == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                || ctx == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        Player holder = inHand ? holder(stack) : null;
        RawAnimation next = pick(holder, stack);
        AnimationController<MatterManipulatorItem> c = state.getController();
        RawAnimation last = LAST.get(c);
        Track t = holder == null ? null : track(holder);
        if (next != last) {
            int ticks = blendFor(last, next);
            c.transitionLength(ticks);
            BLEND.put(c, ticks);
            LAST.put(c, next);
        } else if (t != null && t.restart) {
            c.transitionLength(1);
            c.forceAnimationReset();
        }
        if (t != null) {
            if (next != last || t.restart) t.ringSkip = true;
            float[] in = inertia(next);
            t.ringHz = in[0];
            t.ringZeta = in[1];
            t.restart = false;
        }
        // the signal ramp grinds faster as the strength climbs
        c.setAnimationSpeed(
1);
        state.setAndContinue(next);
        return PlayState.CONTINUE;
    }

    /** The player holding this exact stack, or the local player as a fallback. */
    static Player holder(ItemStack stack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        if (stack != null) {
            for (Player p : mc.level.players()) {
                if (p.getMainHandItem() == stack || p.getOffhandItem() == stack) return p;
            }
        }
        Player local = mc.player;
        if (local != null && local.getMainHandItem().getItem() instanceof MatterManipulatorItem) {
            return local;
        }
        // just put away: vanilla is still lowering it, and it folds away meanwhile
        if (local != null && now(mc) - track(local).putAwayAt < UNSUMMON_LEN + 0.1f) return local;
        return null;
    }

    // ---------------------------------------------------------------- hooks from input and ticks

    /**
     * Client tick: notes who holds the Manipulator, for every player in the world. The animation
     * predicate only runs while the item is drawn, so it would never see a put-away tool leave the
     * hand, and the next summon would not replay its animation.
     */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float now = mc.level.getGameTime() / 20f;
        for (Player pl : mc.level.players()) {
            Track tr = track(pl);
            boolean held = pl.getMainHandItem().getItem() instanceof MatterManipulatorItem;
            if (held && !tr.held) {
                tr.heldSince = now;
                tr.putAwayAt = -100;
            }
            tr.held = held;
        }
        if (TRACKS.size() > 64) TRACKS.keySet().removeIf(id -> mc.level.getEntity(id) == null);
        // opening the upgrade screen gives the same servo turn as a mode switch
        if (mc.player != null && mc.screen instanceof UpgradeScreen && !screenOpen) {
            modeTurn(track(mc.player), now);
        }
        screenOpen = mc.screen instanceof UpgradeScreen;
    }

    private static boolean screenOpen;

    /** Local player pressed left click in this mode (instant left clicks animate from here). */
    public static void leftPress(Player p, OrbMode mode) {
        Track t = track(p);
        t.leftPressAt = now(Minecraft.getInstance());
        t.leftPressMode = mode;
        t.leftPressPlaces =
                mode == OrbMode.MASS
                        && !com.mattermod.beam.MassDriver.carried(p.getMainHandItem()).isAir();
        t.restart = true;
    }

    /** Middle click while summoned: show the tool off. */
    public static void inspect(Player p) {
        Track t = track(p);
        float now = now(Minecraft.getInstance());
        if (now - t.inspectAt > INSPECT_LEN) t.inspectAt = now;
    }

    /**
     * The tool was just put away: it folds while vanilla lowers it, so putting away takes exactly
     * as long as vanilla's own item swap.
     */
    public static void putAway(Player p) {
        track(p).putAwayAt = now(Minecraft.getInstance());
    }

    /** The Energy charge's motor: ring degrees it has driven so far (it never keyframes). */
    private static float spinStep(Track t, float dt) {
        if (t.spinTarget > 0) {
            // the motor runs up to this stage's speed (the ring's inertia lags behind it)
            t.spinSpeed += (t.spinTarget - t.spinSpeed) * (1 - (float) Math.exp(-dt / 0.1f));
        } else if (t.spinSpeed != 0) {
            // let go: the motor keeps turning through the shot and winds down
            t.spinSpeed *= (float) Math.exp(-dt / 0.3f);
            if (t.spinSpeed < 2) t.spinSpeed = 0;
        }
        return t.spinSpeed * dt;
    }

    /**
     * The ring is a physical part with its own inertia. The keyframes (and the Energy charge's
     * motor) only say where the motor drives it; the ring follows on a spring, so every turn gathers
     * speed for a moment at the start and overshoots and settles at the end, and a whirl coasts on
     * after its loop stops. The motor's position only moves with the keyframes inside an animation:
     * never while two animations blend (that's the blend travelling between two poses, not a turn),
     * nor on the first frame of a new animation or after a blend, so nothing ever winds the ring
     * back. Each animation sets how heavy the ring feels ({@link #inertia}). Returns the degrees
     * to add to the keyframed angle {@code raw}.
     */
    static float ring(Player p, float raw, boolean blending) {
        if (p == null) return 0;
        Track t = track(p);
        float now = now(Minecraft.getInstance());
        if (now == t.ringTime) return t.ringAngle - raw;
        float dt = t.ringTime < 0 ? 0 : Math.min(0.1f, now - t.ringTime);
        t.ringTime = now;
        if (Float.isNaN(t.ringRaw)) t.ringRaw = raw;
        // motor movement since last frame; a loop wrapping round (360 back to 0) is not a turn
        float d = raw - t.ringRaw;
        d -= (float) Math.floor((d + 180f) / 360f) * 360f;
        t.ringRaw = raw;
        boolean counts = !blending && !t.ringSkip && !t.ringWasBlending && Math.abs(d) < 45f;
        t.ringSkip = false;
        t.ringWasBlending = blending;
        if (counts) t.ringTarget += d;
        if (dt > 0) {
            t.ringTarget += spinStep(t, dt);
            float w = (float) (2 * Math.PI * t.ringHz), z = t.ringZeta;
            int n = Math.max(1, (int) Math.ceil(dt / 0.004f));
            float h = dt / n;
            for (int i = 0; i < n; i++) {
                float acc = w * w * (t.ringTarget - t.ringAngle) - 2 * z * w * t.ringVel;
                t.ringVel += acc * h;
                t.ringAngle += t.ringVel * h;
            }
        }
        if (t.ringAngle >= 360 || t.ringAngle < 0) {
            float wrap = (float) Math.floor(t.ringAngle / 360f) * 360f;
            t.ringAngle -= wrap;
            t.ringTarget -= wrap;
        }
        return t.ringAngle - raw;
    }

    /**
     * A mode switch: the ring gets a quarter turn from code, not from the keyframes, so switches in
     * quick succession (spamming Alt) stack up into one fast spin that settles where the last one
     * ends, and the prongs' flex starts again on every switch instead of being swallowed.
     */
    private static void modeTurn(Track t, float now) {
        t.modeSwitchAt = now;
        t.restart = true;
        // no more than three quarter turns queued, so a long spam doesn't wind the ring up
        t.ringTarget = Math.min(t.ringTarget + 90, t.ringAngle + 270);
    }

    /** Spring frequency (Hz) and damping of the ring under each animation: its feel of weight. */
    private static float[] inertia(RawAnimation a) {
        if (a == FIRE || FIRE_BY.containsValue(a) || a == GRENADE || a == TETHER || a == TETHER_GRIP)
            return new float[] {7f, 0.45f}; // snappy flicks: quick to follow, a crisp overshoot
        for (RawAnimation c : CHARGE_STAGES) if (a == c) return new float[] {3f, 0.6f};
        if (a == OVERCHARGE) return new float[] {3f, 0.6f};
        if (SPINS.contains(a)) return new float[] {2.8f, 0.55f}; // whirls: heavy, long coast
        if (a == SUMMON || a == UNSUMMON || a == FIDGET || a == MODE_SWITCH || a == INSPECT)
            return new float[] {4.5f, 0.42f}; // flourishes: a lively overshoot
        return new float[] {5f, 0.5f};
    }

    // ---------------------------------------------------------------- the pick

    private static RawAnimation pick(Player p, ItemStack stack) {
        if (p == null) return IDLE;
        Minecraft mc = Minecraft.getInstance();
        float now = now(mc);
        Track t = track(p);
        boolean local = p == mc.player;
        // the tick notices too, but the first frame after a summon may come before it
        if (!t.held && p.getMainHandItem().getItem() instanceof MatterManipulatorItem) {
            t.held = true;
            t.heldSince = now;
            t.putAwayAt = -100;
        }

        boolean rmb = p.isUsingItem() && p.getUseItem().getItem() instanceof MatterManipulatorItem;
        // Hydraulic / Pyrotechnic charge on left click too (known for the local player only)
        int leftCharge = local ? BeamRenderState.leftChargeTicks : 0;
        boolean using = rmb || leftCharge > 0;
        OrbMode tool = stack != null && !stack.isEmpty() ? OrbMode.get(stack) : OrbMode.ENERGY;
        // the projectile charging: the left click's own, or the right click's
        OrbMode mode = rmb ? tool.rmbShot() : tool;
        if (t.using && !using) {
            t.releasedAt = now;
            t.releaseFires = !mode.isHold();
            t.releaseMode = mode;
            t.restart = true;
        }
        t.using = using;

        if (t.lastMode != null && t.lastMode != tool) modeTurn(t, now);
        t.lastMode = tool;

        // Tether hook / Mass Driver block state, from this player's orbs (checked once per tick)
        long tick = mc.level.getGameTime();
        if (t.orbTick != tick) {
            t.orbTick = tick;
            t.orbCaught = false;
            t.orbFlying = false;
            t.orbDrift = false;
            if (mode == OrbMode.TETHER || mode == OrbMode.GROWTH) {
                for (BeamBallEntity orb :
                        mc.level.getEntitiesOfClass(
                                BeamBallEntity.class,
                                p.getBoundingBox().inflate(96),
                                o -> o.getOwner() == p)) {
                    if (orb.getMode() == OrbMode.TETHER && orb.isStuck() && !orb.isAnchor())
                        t.orbCaught = true;
                    if (orb.getMode() == OrbMode.TETHER && !orb.isStuck() && !orb.isAnchor())
                        t.orbFlying = true;
                    if (orb.isDrifting()) t.orbDrift = true;
                }
            }
        }
        if (t.caught && !t.orbCaught) t.caughtEndedAt = now;
        t.caught = t.orbCaught;
        t.flying = t.orbFlying;
        if (t.drift && !t.orbDrift) t.driftEndedAt = now;
        t.drift = t.orbDrift;
        // Mass Driver: what the tool carries is on the stack itself (thrown or dropped blocks are
        // still in the air as orbs, but no longer held)
        boolean carrying =
                tool == OrbMode.MASS
                        && stack != null
                        && !com.mattermod.beam.MassDriver.carried(stack).isAir();
        if (t.carrying && !carrying) t.carryEndedAt = now;
        t.carrying = carrying;

        boolean beaming =
                local ? BeamRenderState.beamActive : RemoteBeams.active().containsKey(p.getId());
        if (beaming && !t.beaming) t.beamSince = now;
        if (!beaming && t.beaming) t.beamEndedAt = now;
        if (beaming) t.beamTill = p.isShiftKeyDown();
        t.beaming = beaming;

        // Energy charge spin (everything else lets it run down)
        t.spinTarget = 0;

        RawAnimation a = choose(p, stack, t, now, local, rmb, leftCharge, using, tool, mode);
        if (a == IDLE) {
            if (t.idleSince < 0) t.idleSince = now;
            if (now - t.idleSince > t.nextFidget) {
                t.fidgetAt = now;
                t.idleSince = now + FIDGET_LEN;
                // rare, and never on a schedule you could feel
                t.nextFidget = 40 + (float) Math.random() * 80;
            }
            if (now - t.fidgetAt < FIDGET_LEN - HANDOVER) a = FIDGET;
        } else {
            t.idleSince = -100;
        }
        return a;
    }

    private static RawAnimation choose(
            Player p,
            ItemStack stack,
            Track t,
            float now,
            boolean local,
            boolean rmb,
            int leftCharge,
            boolean using,
            OrbMode tool,
            OrbMode mode) {
        if (!t.held) return now - t.putAwayAt < UNSUMMON_LEN + 0.1f ? UNSUMMON : IDLE;

        if (t.caught) return TETHER_GRIP;
        // the hook (fired with left click) is out: the claw stays reached open until it catches
        if (t.flying) return TETHER;
        if (now - t.caughtEndedAt < TETHER_RELEASE_LEN - HANDOVER) return TETHER_RELEASE;

        if (using) {
            if (mode == OrbMode.TETHER) return TETHER;
            if (mode == OrbMode.MASS) return MASS_REACH;
            if (mode == OrbMode.ENERGY || !CHARGE.containsKey(mode)) {
                int ticksHeld = rmb ? p.getTicksUsingItem() : leftCharge;
                int full = Math.max(1, Traits.chargeTicks(stack));
                int stage = Math.min(2, ticksHeld * 3 / full);
                t.spinTarget = STAGE_SPIN[stage];
                if (ticksHeld > full + OVERCHARGE_AFTER * 20) return OVERCHARGE;
                return CHARGE_STAGES[stage];
            }
            return CHARGE.get(mode);
        }

        // Mass Driver: thrown (right-click release) or set down (left click while carrying);
        // both timed from the input itself, not from the server's answer
        if (t.releaseMode == OrbMode.MASS && now - t.releasedAt < MASS_RELEASE_LEN - HANDOVER)
            return MASS_RELEASE;
        if (local && t.leftPressMode == OrbMode.MASS && t.leftPressPlaces) {
            if (now - t.leftPressAt < MASS_PLACE_LEN - HANDOVER) return MASS_PLACE;
        }

        // left clicks of the local player that aren't a charge
        if (local) {
            OrbMode held = BeamRenderState.leftMode;
            if (held == OrbMode.MASS && !t.leftPressPlaces) return MASS_GRAB;
            if (held == OrbMode.LIGHT) return BEACON;
            float since = now - t.leftPressAt;
            if (t.leftPressMode == OrbMode.TETHER && since < PULL_LEN - HANDOVER) return TETHER;
            if (t.leftPressMode == OrbMode.GUST && since < GRENADE_LEN - HANDOVER) return GRENADE;
            if (t.leftPressMode == OrbMode.GROWTH && since < HARVEST_LEN - HANDOVER) return HARVEST;
            // Golem orders: the old signal click, a firm middle press
            if (t.leftPressMode == OrbMode.GOLEM && since < CYCLE_LEN - HANDOVER) return CYCLE[1];
        }
        if (t.carrying) return MASS_GRIP;

        // Pollen drift: the guide pose while it flies, the release when it ends
        boolean growthShot = t.releaseFires && t.releaseMode == OrbMode.GROWTH;
        if (t.drift || (growthShot && now - t.releasedAt < DRIFT_WAIT && t.driftEndedAt < t.releasedAt))
            return GROWTH_GUIDE;
        if (growthShot) {
            float end = t.driftEndedAt > t.releasedAt ? t.driftEndedAt : t.releasedAt + DRIFT_WAIT;
            if (now - end < GROWTH_END_LEN - HANDOVER) return GROWTH_END;
        }

        if (t.releaseFires && !growthShot && t.releaseMode != OrbMode.MASS) {
            RawAnimation fire = FIRE_BY.getOrDefault(t.releaseMode, FIRE);
            float len = FIRE_LEN.getOrDefault(t.releaseMode, FIRE_LEN_ENERGY);
            if (now - t.releasedAt < len - HANDOVER) return fire;
        }
        if (now - t.heldSince < SUMMON_LEN - HANDOVER) return SUMMON;
        if (now - t.inspectAt < INSPECT_LEN - HANDOVER) return INSPECT;
        if (now - t.modeSwitchAt < MODE_SWITCH_LEN - HANDOVER) return MODE_SWITCH;

        if (t.beaming) {
            if (p.isShiftKeyDown()) return TILL;
            if (now - t.beamSince < BEAM_START_LEN - HANDOVER) return BEAM_START;
            // the beam grows in power while it keeps hitting; so does its motion
            int level = local ? BeamRenderState.combatLevel : 1;
            return MINE[Math.max(0, Math.min(MINE.length - 1, level - 1))];
        }
        if (!t.beamTill && now - t.beamEndedAt < MINE_END_LEN - HANDOVER) return MINE_END;
        if (p.swinging) return MINE[0]; // melee shares the mining motion
        return IDLE;
    }
}
