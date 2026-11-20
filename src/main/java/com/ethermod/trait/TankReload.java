package com.ethermod.trait;

import com.ethermod.item.OrbMode;
import com.ethermod.manipulator.Manipulator;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** The shared Ether tank's reload: the wait after the last spend, the lead-in animation, the refill. */
public final class TankReload {
    private TankReload() {}

    @SubscribeEvent
    public static void tick(PlayerTickEvent.Post ev) {
        if (!(ev.getEntity() instanceof ServerPlayer p)) return;
        var st = Manipulator.stack(p);
        boolean changed = false;
        // The shared tank reloads Jhin-style: nothing trickles back while you keep firing; once no Ether has been
        // spent for the reload delay (shorter at higher tiers), the whole tank fills at once. The reload animation leads in.
        int cap = OrbMode.capacity(st), tank = OrbMode.tank(st);
        var data = p.getPersistentData();
        // spent since last tick: restart the wait
        if (tank < data.getInt("mm_tank_last")) {
            // fired during the reload animation: call it off
            if (reloadDelay(st) - data.getInt("mm_refill_ether") <= RELOAD_LEAD)
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        p, new com.ethermod.network.payload.ReloadPayload(-100));
            data.putInt("mm_refill_ether", 0);
        }
        data.putInt("mm_tank_last", tank);
        if (tank < cap) {
            int delay = reloadDelay(st);
            int idle = data.getInt("mm_refill_ether") + 1;
            int lead = delay - idle;
            // the reload (FX bench "intake"): the animation starts RELOAD_LEAD ticks before the fill, so its clamp
            // lands on it; Ether is drawn in with a soft charge, and a small click when the tank is full
            if (lead == Math.min(RELOAD_LEAD, delay - 1))
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                        p, new com.ethermod.network.payload.ReloadPayload(lead));
            if (lead == RELOAD_LEAD - 8)
                p.playNotifySound(
                        com.ethermod.sound.ModSounds.UI_RESPAWN_ANCHOR_CHARGE,
                        net.minecraft.sounds.SoundSource.PLAYERS,
                        0.18f,
                        1.5f);
            if (idle >= delay) {
                OrbMode.setTank(st, cap);
                data.putInt("mm_tank_last", cap);
                idle = 0;
                changed = true;
                p.playNotifySound(
                        com.ethermod.sound.ModSounds.UI_COPPER_BULB_TURN_OFF,
                        net.minecraft.sounds.SoundSource.PLAYERS,
                        0.1f,
                        1.3f);
            }
            data.putInt("mm_refill_ether", idle);
        } else data.putInt("mm_refill_ether", 0);
        if (changed) Manipulator.sync(p);
    }

    /** Ticks from the reload animation's start to its clamp (1.16s of the 1.6s animation). */
    static final int RELOAD_LEAD = 23;

    /** Ticks without spending Ether before the tank reloads: from the server config, Prototype to Overclocked. */
    public static int reloadDelay(net.minecraft.world.item.ItemStack st) {
        return com.ethermod.config.ServerConfig.reloadTicks(com.ethermod.item.ManipulatorStage.get(st).ordinal());
    }
}
