package com.ethermod.client;

import com.ethermod.trait.MovementPhysics;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;

public final class GravityRender {
    @SubscribeEvent
    public static void before(RenderPlayerEvent.Pre event) {
        var p = event.getEntity();
        if (!MovementPhysics.isTurning(p)) return;
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
        event.getPoseStack().mulPose(rotation);
    }

    @SubscribeEvent
    public static void after(RenderPlayerEvent.Post event) {
        if (MovementPhysics.isTurning(event.getEntity())) event.getPoseStack().popPose();
    }
}
