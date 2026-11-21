package com.ethermod.client;

import static com.ethermod.client.ManipulatorClips.*;

import com.ethermod.beam.RemoteBeams;
import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.item.OrbMode;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.geckolib.animation.AnimationController;
import com.geckolib.animation.state.AnimationTest;
import com.geckolib.animation.object.PlayState;
import com.geckolib.animation.RawAnimation;
import com.geckolib.constant.DataTickets;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client-only: picks the Manipulator's keyframed animation from what its holder is doing. Loop and
 * hold behaviour come from the animation file. {@link EtherManipulatorItem#registerControllers}
 * reaches it through the client's SideHooks. The clips live in {@link ManipulatorClips}, the
 * per-holder state in {@link ManipulatorTrack} and the choice in {@link ManipulatorAnimationPick}.
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

    private static final Map<Integer, ManipulatorTrack> TRACKS = new HashMap<>();

    /** Level change: tracks are keyed by entity id, which means nothing in the next world. */
    public static void clearCaches() {
        TRACKS.clear();
    }

    static ManipulatorTrack track(Player p) {
        return TRACKS.computeIfAbsent(p.getId(), id -> new ManipulatorTrack());
    }

    /**
     * Whether the holder should hold the tool up and aimed (the bow pose): while any input is in
     * use, a little after a click or a shot, and while a hook, a carried block or a drift is out.
     */
    public static boolean aiming(Player p) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        ManipulatorTrack t = TRACKS.get(p.getId());
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

    static float now(Minecraft mc) {
        return (mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)) / 20f;
    }

    public static AnimationController<EtherManipulatorItem> controller(
            EtherManipulatorItem item) {
        // (blends ease out past the target and settle back: see predicate)
        return new AnimationController<>("main", BLEND_TICKS, ManipulatorAnimations::predicate);
    }

    /** What each controller played last, and the blend chosen into it. */
    private static final Map<AnimationController<?>, RawAnimation> LAST = new WeakHashMap<>();

    private static final Map<AnimationController<?>, Integer> BLEND = new WeakHashMap<>();


    private static PlayState predicate(AnimationTest<EtherManipulatorItem> state) {
        ItemStack stack = state.getDataOrDefault(ManipulatorItemRenderer.STACK, ItemStack.EMPTY);
        // only a tool in a hand follows its holder: an icon drawn meanwhile (creative tab, slot, frame) must not
        // consume the holder's restarts and ring state, or both spin erratically
        var ctx = state.getData(DataTickets.ITEM_RENDER_PERSPECTIVE);
        boolean inHand = ctx == null || ctx.firstPerson() || ctx == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                || ctx == net.minecraft.world.item.ItemDisplayContext.THIRD_PERSON_LEFT_HAND;
        Player holder = inHand ? holder(stack) : null;
        RawAnimation next = ManipulatorAnimationPick.pick(holder, stack);
        if (next == RELOAD && ctx != null && !ctx.firstPerson()) next = RELOAD_TP;
        AnimationController<EtherManipulatorItem> c = state.controller();
        RawAnimation last = LAST.get(c);
        ManipulatorTrack t = holder == null ? null : track(holder);
        if (next != last) {
            int ticks = blendFor(last, next);
            c.setTransitionTicks(ticks);
            BLEND.put(c, ticks);
            LAST.put(c, next);
        } else if (t != null && t.restart) {
            c.setTransitionTicks(1);
            c.reset();
        }
        if (t != null) {
            if (next != last || t.restart) t.ringSkip = true;
            float[] in = inertia(next);
            t.ringHz = in[0];
            t.ringZeta = in[1];
            t.restart = false;
        }
        // the signal ramp grinds faster as the strength climbs
        c.setAnimationSpeed(1);
        state.setAndContinue(next);
        // Blends between animations ease out past the target and settle back (an overshoot).
        // Only while blending: inside an animation the keyframes keep their own easing.
        c.setOverrideEasingType(c.isTransitioning() ? easing(BLEND.getOrDefault(c, BLEND_TICKS)) : null);
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
        if (local != null && local.getMainHandItem().getItem() instanceof EtherManipulatorItem) {
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
            ManipulatorTrack tr = track(pl);
            boolean held = pl.getMainHandItem().getItem() instanceof EtherManipulatorItem;
            if (held && !tr.held) {
                tr.heldSince = now;
                tr.putAwayAt = -100;
            }
            tr.held = held;
        }
        if (TRACKS.size() > 64) TRACKS.keySet().removeIf(id -> mc.level.getEntity(id) == null);
        // opening the upgrade screen gives the same servo turn as a mode switch
        if (mc.player != null && mc.gui.screen() instanceof UpgradeScreen && !screenOpen) {
            modeTurn(track(mc.player), now);
        }
        screenOpen = mc.gui.screen() instanceof UpgradeScreen;
    }

    private static boolean screenOpen;

    /** Local player pressed left click in this mode (instant left clicks animate from here). */
    /** Right click in Pneumatic mode: the Stasis Grenade throw. */
    public static void grenadeThrow(Player p) {
        ManipulatorTrack t = track(p);
        t.grenadeAt = now(Minecraft.getInstance());
        t.restart = true;
    }

    public static void leftPress(Player p, OrbMode mode) {
        ManipulatorTrack t = track(p);
        t.leftPressAt = now(Minecraft.getInstance());
        t.leftPressMode = mode;
        t.leftPressPlaces =
                mode == OrbMode.MASS
                        && !com.ethermod.beam.MassDriver.carried(p.getMainHandItem()).isAir();
        t.restart = true;
    }

    /** Whether the local player's reload animation is playing (the server announced the fill). */
    public static boolean reloading(float now) {
        float t = reloadTime(now);
        return t >= 0 && t < RELOAD_LEN - HANDOVER;
    }

    /** Seconds into the reload animation (negative or past its length when none plays). */
    public static float reloadTime(float now) {
        long fill = com.ethermod.beam.RemoteBeams.reloadFillAt();
        if (fill == Long.MIN_VALUE) return -1;
        return now - (fill / 20f - RELOAD_CLAMP);
    }

    /** Seconds into the reload animation, for the star and the Ether motes. */
    public static float reloadTime() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? -1 : reloadTime(now(mc));
    }

    /** Middle click while summoned: show the tool off. */
    public static void inspect(Player p) {
        ManipulatorTrack t = track(p);
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
    private static float spinStep(ManipulatorTrack t, float dt) {
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
        ManipulatorTrack t = track(p);
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
    static void modeTurn(ManipulatorTrack t, float now) {
        t.modeSwitchAt = now;
        t.restart = true;
        // no more than three quarter turns queued, so a long spam doesn't wind the ring up
        t.ringTarget = Math.min(t.ringTarget + 90, t.ringAngle + 270);
    }


}
