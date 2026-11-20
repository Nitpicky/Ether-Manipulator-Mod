package com.ethermod.advancement;

import com.ethermod.manipulator.Manipulator;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The hidden per-Module advancements whose moments aren't inside one Module's own code. */
public final class SecretAdvancements {
    private SecretAdvancements() {}

    /** Pyrotechnic shots mark their owner for this long, so the fire they stand in counts as theirs. */
    public static final String PYRO_TAG = "mm_pyro_until";

    private static final Map<UUID, Integer> BEAM_KILLS = new HashMap<>();

    /** A new beam starts: (Don't) Let It Go counts kills from zero. */
    public static void beamStarted(ServerPlayer p) {
        BEAM_KILLS.put(p.getUUID(), 0);
    }

    /** Logout ends the beam, and with it the count (a new beam starts from zero anyway). */
    public static void forget(UUID player) {
        BEAM_KILLS.remove(player);
    }

    public static void clear() {
        BEAM_KILLS.clear();
    }

    /** Plasmic, (Don't) Let It Go: three kills with one beam, never letting go. */
    @SubscribeEvent
    public static void kill(LivingDeathEvent ev) {
        if (!(ev.getSource().getEntity() instanceof ServerPlayer sp) || ev.getSource().getDirectEntity() != sp) return;
        if (!com.ethermod.event.ModEvents.beaming(sp) || !Manipulator.isSummoned(sp)) return;
        int n = BEAM_KILLS.merge(sp.getUUID(), 1, Integer::sum);
        if (n >= 3) ManipulatorTrigger.fire(sp, "let_it_go");
    }

    /** Pyrotechnic, This Is Fine: drinking something while standing in fire you started. */
    @SubscribeEvent
    public static void drink(LivingEntityUseItemEvent.Finish ev) {
        if (!(ev.getEntity() instanceof ServerPlayer sp) || ev.getItem().getUseAnimation() != UseAnim.DRINK) return;
        if (sp.getPersistentData().getLong(PYRO_TAG) < sp.level().getGameTime()) return;
        var at = sp.blockPosition();
        if (sp.level().getBlockState(at).getBlock() instanceof BaseFireBlock
                || sp.level().getBlockState(at.above()).getBlock() instanceof BaseFireBlock)
            ManipulatorTrigger.fire(sp, "this_is_fine");
    }

    /** Photonic, Night Light: asleep in a bed lit only by bounce lights (no torch or other light nearby). */
    @SubscribeEvent
    public static void sleep(PlayerTickEvent.Post ev) {
        if (!(ev.getEntity() instanceof ServerPlayer sp) || !sp.isSleeping() || sp.getSleepTimer() != 20) return;
        ServerLevel sl = sp.level();
        BlockPos bed = sp.getSleepingPos().orElse(sp.blockPosition());
        boolean bounce = false;
        for (BlockPos q : BlockPos.betweenClosed(bed.offset(-8, -4, -8), bed.offset(8, 4, 8))) {
            var s = sl.getBlockState(q);
            if (s.is(Blocks.LIGHT)) {
                if (q.distSqr(bed) <= 36) bounce = true;
            } else if (s.getLightEmission(sl, q) > 0) return; // another light source: not "only"
        }
        if (bounce && sl.getBrightness(net.minecraft.world.level.LightLayer.SKY, bed.above()) < 8 || bounce && sl.isNight())
            ManipulatorTrigger.fire(sp, "night_light");
    }
}
