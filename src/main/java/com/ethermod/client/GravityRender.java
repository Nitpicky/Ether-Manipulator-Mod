package com.ethermod.client;

import com.ethermod.trait.MovementPhysics;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

public final class GravityRender {
    /** The player a render state was taken from (26.3 render events carry only the state). */
    private static Player player(RenderPlayerEvent<?> event) {
        var level = Minecraft.getInstance().level;
        return level != null && level.getEntity(event.getRenderState().id) instanceof Player p ? p : null;
    }

    @SubscribeEvent
    public static void before(RenderPlayerEvent.Pre<?> event) {
        var p = player(event);
        if (p == null || !MovementPhysics.isTurning(p)) return;
        var rotation = MovementPhysics.viewRotation(p, event.getPartialTick());
        var targetUp = MovementPhysics.vector(MovementPhysics.down(p).getOpposite());
        var visualUp = rotation.transform(new org.joml.Vector3f(0, 1, 0));
        double half = p.getBbHeight() / 2;
        event.getPoseStack().pushPose();
        event.getPoseStack()
                .translate(
                        (targetUp.x - visualUp.x) * half,
                        (targetUp.y - visualUp.y) * half,
                        (targetUp.z - visualUp.z) * half);
        event.getPoseStack().rotate(rotation);
    }

    @SubscribeEvent
    public static void after(RenderPlayerEvent.Post<?> event) {
        var p = player(event);
        if (p != null && MovementPhysics.isTurning(p)) event.getPoseStack().popPose();
    }
}
