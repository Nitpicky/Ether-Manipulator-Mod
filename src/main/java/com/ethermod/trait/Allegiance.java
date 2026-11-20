package com.ethermod.trait;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** Who a player's effects may hit: allies (self, own summons, team, own pets) and the PvP rules. */
public final class Allegiance {
    private Allegiance() {}

    public static boolean ally(ServerPlayer owner, LivingEntity e) {
        return e == owner
                || (e.getPersistentData().read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).isPresent()
                        && owner.getUUID().equals(e.getPersistentData().read("mm_summoner", net.minecraft.core.UUIDUtil.CODEC).orElse(null)))
                || owner.isAlliedTo(e)
                || (e instanceof net.minecraft.world.entity.TamableAnimal t
                        && owner.getUUID().equals(t.getOwnerUUID()));
    }

    public static boolean enemy(ServerPlayer owner, LivingEntity e) {
        // other players only when both the hurt_players setting and the server's PvP allow it
        if (e instanceof Player && e != owner
                && !(com.ethermod.config.ServerConfig.hurtPlayers() && owner.server.isPvpAllowed()))
            return false;
        return enemyIgnoringPvp(owner, e);
    }

    /** As {@link #enemy}, without the player rule: for callers with their own player setting. */
    public static boolean enemyIgnoringPvp(ServerPlayer owner, LivingEntity e) {
        return !com.ethermod.beam.Mounts.own(owner, e)
                && e.isAlive()
                && !ally(owner, e)
                && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand);
    }
}
