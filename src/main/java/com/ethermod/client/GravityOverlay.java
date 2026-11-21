package com.ethermod.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ExtractBlockScreenEffectEvent;

/**
 * Under sideways gravity the "in a wall" screen overlay follows the eye where it really is: the
 * game samples a box around the eye laid out for normal gravity, which can poke into the floor
 * you now stand on and black out the screen. While gravity is turned, the overlay only shows when
 * a block covers the turned eye.
 */
@EventBusSubscriber(modid = "ethermod", value = Dist.CLIENT)
public final class GravityOverlay {
    private GravityOverlay() {}

    @SubscribeEvent
    public static void onOverlay(ExtractBlockScreenEffectEvent ev) {
        if (ev.getOverlayType() != ExtractBlockScreenEffectEvent.OverlayType.BLOCK) return;
        if (!com.ethermod.trait.MovementPhysics.isTurning(ev.getPlayer())) return;
        if (blockAtEye(ev.getPlayer()) == null) ev.setCanceled(true);
    }

    /** The view-blocking block around the player's turned eye, or null. */
    public static BlockState blockAtEye(Player player) {
        Vec3 eye = player.getEyePosition();
        for (int i = 0; i < 8; i++) {
            Vec3 offset =
                    new Vec3(
                            ((i & 1) == 0 ? -.4 : .4) * player.getBbWidth(),
                            ((i & 2) == 0 ? -.05 : .05) * player.getScale(),
                            ((i & 4) == 0 ? -.4 : .4) * player.getBbWidth());
            Vec3 at = eye.add(com.ethermod.trait.MovementPhysics.orientView(player, offset, 0));
            BlockPos pos = BlockPos.containing(at);
            var state = player.level().getBlockState(pos);
            if (state.getRenderShape() != net.minecraft.world.level.block.RenderShape.INVISIBLE
                    && state.isViewBlocking(
                            player.level(), pos, net.minecraft.world.phys.AABB.ofSize(at, 1e-3, 1e-3, 1e-3)))
                return state;
        }
        return null;
    }
}
