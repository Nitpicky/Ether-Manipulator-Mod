package com.ethermod.client;

import static com.ethermod.client.ManipulatorAnimations.modeTurn;
import static com.ethermod.client.ManipulatorAnimations.now;
import static com.ethermod.client.ManipulatorAnimations.reloading;
import static com.ethermod.client.ManipulatorAnimations.track;
import static com.ethermod.client.ManipulatorClips.*;

import com.ethermod.beam.BeamBallEntity;
import com.ethermod.beam.RemoteBeams;
import com.ethermod.client.beam.BeamRenderState;
import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.item.OrbMode;
import com.ethermod.trait.Traits;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import com.geckolib.animation.RawAnimation;

/** Which animation the Manipulator plays, from what its holder is doing (see {@link ManipulatorAnimations}). */
final class ManipulatorAnimationPick {

    private ManipulatorAnimationPick() {}

    // ---------------------------------------------------------------- the pick

    static RawAnimation pick(Player p, ItemStack stack) {
        if (p == null) return IDLE;
        Minecraft mc = Minecraft.getInstance();
        float now = now(mc);
        ManipulatorTrack t = track(p);
        boolean local = p == mc.player;
        // the tick notices too, but the first frame after a summon may come before it
        if (!t.held && p.getMainHandItem().getItem() instanceof EtherManipulatorItem) {
            t.held = true;
            t.heldSince = now;
            t.putAwayAt = -100;
        }

        boolean rmb = p.isUsingItem() && p.getUseItem().getItem() instanceof EtherManipulatorItem;
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
                        && !com.ethermod.beam.MassDriver.carried(stack).isAir();
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

    static RawAnimation choose(
            Player p,
            ItemStack stack,
            ManipulatorTrack t,
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
            if (now - t.grenadeAt < GRENADE_LEN - HANDOVER) return GRENADE;
            if (t.leftPressMode == OrbMode.GROWTH && since < HARVEST_LEN - HANDOVER) return HARVEST;
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
        if (local && !t.beaming && !BeamRenderState.isCharging && reloading(now)) return RELOAD;

        if (t.beaming) {
            if (p.isShiftKeyDown()) return TILL;
            if (now - t.beamSince < BEAM_START_LEN - HANDOVER) return BEAM_START;
            // the beam grows in power while it keeps hitting; so does its motion
            int level = local ? BeamRenderState.combatLevel : 1;
            return MINE[Math.max(0, Math.min(MINE.length - 1, level - 1))];
        }
        if (!t.beamTill && now - t.beamEndedAt < MINE_END_LEN - HANDOVER) return MINE_END;
        if (p.isSwinging()) return MINE[0]; // melee shares the mining motion
        return IDLE;
    }
}
