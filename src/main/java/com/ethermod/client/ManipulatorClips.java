package com.ethermod.client;

import com.ethermod.item.OrbMode;

import software.bernie.geckolib.animation.EasingType;
import software.bernie.geckolib.animation.RawAnimation;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Manipulator's animation clips (see {@link ManipulatorAnimations}): their names and lengths,
 * how long each blend into them lasts and how heavy the ring feels under each.
 */
final class ManipulatorClips {

    private ManipulatorClips() {}

    static final String PREFIX = "animation.ether_manipulator.";

    static RawAnimation anim(String name) {
        return RawAnimation.begin().thenPlay(PREFIX + name);
    }

    static final RawAnimation IDLE = anim("idle");
    static final RawAnimation FIDGET = anim("fidget");
    static final RawAnimation SUMMON = anim("summon");
    static final RawAnimation UNSUMMON = anim("unsummon");
    static final RawAnimation MODE_SWITCH = anim("mode_switch");
    static final RawAnimation INSPECT = anim("inspect");
    /** Reload ("intake", FX bench): tips up, prongs flare, the ring spins the Ether in, prongs clamp at 1.16s. */
    static final RawAnimation RELOAD = anim("reload");
    /** The same without moving the tool itself: third person only moves prongs and ring. */
    static final RawAnimation RELOAD_TP = anim("reload_tp");
    static final float RELOAD_LEN = 1.6f, RELOAD_CLAMP = 1.16f;
    static final RawAnimation BEAM_START = anim("beam_start");
    static final RawAnimation[] MINE = {
        anim("mine"), anim("mine_2"), anim("mine_3"), anim("mine_4")
    };
    static final RawAnimation MINE_END = anim("mine_end");
    static final RawAnimation TILL = anim("till");
    static final RawAnimation OVERCHARGE = anim("overcharge");
    static final RawAnimation TETHER = anim("tether");
    static final RawAnimation TETHER_GRIP = anim("tether_grip");
    static final RawAnimation TETHER_RELEASE = anim("tether_release");
    static final RawAnimation GRENADE = anim("grenade");
    static final RawAnimation BEACON = anim("beacon");
    /** Botanic left click: a sickle sweep over the crops. */
    static final RawAnimation HARVEST = anim("harvest");
    // pollen drift: the push that sends it off, then the guide pose while it flies
    static final RawAnimation GROWTH_GUIDE =
            RawAnimation.begin()
                    .thenPlay(PREFIX + "growth_release")
                    .thenLoop(PREFIX + "growth");
    static final RawAnimation GROWTH_END = anim("fire_growth");
    static final RawAnimation MASS_REACH = anim("mass_driver");
    // a quick claw snap onto the first block, then a clamp every 3 ticks while sweeping
    static final RawAnimation MASS_GRAB =
            RawAnimation.begin()
                    .thenPlay(PREFIX + "mass_grab")
                    .thenLoop(PREFIX + "mass_sweep");
    static final RawAnimation MASS_GRIP = anim("mass_grip");
    static final RawAnimation MASS_RELEASE = anim("mass_release");
    static final RawAnimation MASS_PLACE = anim("mass_place");

    /** Energy charge: each stage opens further; the spin itself comes from {@link #spin}. */
    static final RawAnimation[] CHARGE_STAGES = new RawAnimation[3];

    static {
        for (int i = 0; i < 3; i++) {
            CHARGE_STAGES[i] =
                    RawAnimation.begin()
                            .thenPlay(PREFIX + "charge_" + (i + 1))
                            .thenLoop(PREFIX + "charge_" + (i + 1) + "_loop");
        }
    }

    /** Ring spin of each Energy charge stage, in degrees per second. */
    static final float[] STAGE_SPIN = {330f, 540f, 860f};

    static final Map<OrbMode, RawAnimation> CHARGE = new EnumMap<>(OrbMode.class);
    /** The release after each charged shot; Energy (and anything unlisted) uses "fire". */
    static final Map<OrbMode, RawAnimation> FIRE_BY = new EnumMap<>(OrbMode.class);

    static final Map<OrbMode, Float> FIRE_LEN = new EnumMap<>(OrbMode.class);
    static final RawAnimation FIRE = anim("fire");

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

    static void fire(OrbMode mode, String name, float len) {
        FIRE_BY.put(mode, anim(name));
        FIRE_LEN.put(mode, len);
    }

    // Lengths of the one-shot animations, in seconds (see the animation file). A one-shot hands
    // over a little before its last frame, which already matches the next pose, so the
    // controller never sits on a finished animation (that's what made summon jump to idle).
    static final float HANDOVER = 0.05f;
    static final float SUMMON_LEN = 0.75f;
    static final float UNSUMMON_LEN = 0.2f;
    static final float MODE_SWITCH_LEN = 0.48f;
    static final float FIDGET_LEN = 1.5f;
    static final float INSPECT_LEN = 2.5f;
    static final float BEAM_START_LEN = 0.1f;
    static final float MINE_END_LEN = 0.32f;
    static final float GROWTH_END_LEN = 0.75f;
    /** After a Growth release, how long the guide pose waits for the drift to show up. */
    static final float DRIFT_WAIT = 0.4f;
    static final float FIRE_LEN_ENERGY = 0.6f;
    static final float TETHER_RELEASE_LEN = 0.45f;
    static final float PULL_LEN = 0.5f;
    static final float GRENADE_LEN = 0.55f;
    static final float HARVEST_LEN = 0.66f;
    static final float MASS_RELEASE_LEN = 0.45f;
    static final float MASS_PLACE_LEN = 0.3f;

    /** Seconds a full charge is held before the prongs start to strain. */
    static final float OVERCHARGE_AFTER = 2f;

    /**
     * Short cuts (an animation that starts where the last one ended) blend straight; longer ones
     * get the eased overshoot, which needs a few ticks to read rather than pop.
     */
    static EasingType easing(int ticks) {
        if (ticks <= 2) return EasingType.LINEAR;
        if (ticks == 3) return EasingType.EASE_IN_OUT_SINE;
        return EasingType.EASE_OUT_BACK;
    }

    /** Loops whose ring whirls: the ring feels heaviest under them, so it coasts on after. */
    static final Set<RawAnimation> SPINS = new HashSet<>();

    /** One-shots whose last frame is exactly the idle pose, so idle can follow almost unblended. */
    static final Set<RawAnimation> ENDS_ON_IDLE = new HashSet<>();

    static {
        ENDS_ON_IDLE.addAll(
                List.of(
                        SUMMON, MODE_SWITCH, FIDGET, INSPECT, FIRE, TETHER_RELEASE, GRENADE, HARVEST,
                        MASS_PLACE, MINE_END, GROWTH_END, RELOAD, RELOAD_TP));
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
    static int blendFor(RawAnimation from, RawAnimation to) {
        if (to == SUMMON) return 0; // nothing was showing before it
        if (to == CHARGE_STAGES[1] || to == CHARGE_STAGES[2] || to == OVERCHARGE) return 1;
        if (to == FIRE) return 1;
        if (FIRE_BY.containsValue(to)) return 2;
        if (to == TETHER_RELEASE || to == MASS_PLACE || to == MASS_RELEASE) return 2;
        if (to == TETHER_GRIP) return 1; // the claw clamps on the hit
        if (to == TETHER) return 1; // the reach bursts open on the click
        if (to == GRENADE || to == HARVEST) return 2;
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
    static final int BLEND_TICKS = 6;

    /** Spring frequency (Hz) and damping of the ring under each animation: its feel of weight. */
    static float[] inertia(RawAnimation a) {
        if (a == FIRE || FIRE_BY.containsValue(a) || a == GRENADE || a == TETHER || a == TETHER_GRIP)
            return new float[] {7f, 0.45f}; // snappy flicks: quick to follow, a crisp overshoot
        for (RawAnimation c : CHARGE_STAGES) if (a == c) return new float[] {3f, 0.6f};
        if (a == OVERCHARGE) return new float[] {3f, 0.6f};
        if (SPINS.contains(a)) return new float[] {2.8f, 0.55f}; // whirls: heavy, long coast
        if (a == SUMMON || a == UNSUMMON || a == FIDGET || a == MODE_SWITCH || a == INSPECT)
            return new float[] {4.5f, 0.42f}; // flourishes: a lively overshoot
        return new float[] {5f, 0.5f};
    }
}
