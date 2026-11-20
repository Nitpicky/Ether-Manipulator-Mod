package com.ethermod.client;

import com.ethermod.SideHooks;
import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.network.payload.ModulePayloads;

import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import com.geckolib.animation.AnimationController;

/** The client's {@link SideHooks}: forwards each hook to the client class that does the work. */
@OnlyIn(Dist.CLIENT)
final class ClientSideHooks extends SideHooks {

    @Override
    public IClientItemExtensions manipulatorExtensions() {
        return ManipulatorClientExtensions.INSTANCE;
    }

    @Override
    public AnimationController<EtherManipulatorItem> manipulatorController(EtherManipulatorItem item) {
        return ManipulatorAnimations.controller(item);
    }

    @Override
    public void grenadeThrow(Player player) {
        ManipulatorAnimations.grenadeThrow(player);
    }

    @Override
    public void waterCurrent(ModulePayloads.WaterCurrent p) {
        ClientPayloadHandlers.waterCurrent(p);
    }

    @Override
    public void soulFlame(ModulePayloads.SoulFlame p) {
        ClientPayloadHandlers.soulFlame(p);
    }

    @Override
    public void gravity(int entity, int direction, long start) {
        ClientMobility.gravity(entity, direction, start);
    }

    @Override
    public void dryFire() {
        DryFireFx.fire();
    }

    @Override
    public void stasisForce(int entity, float x, float y, float z) {
        StasisFx.force(entity, x, y, z);
    }

    @Override
    public void stasisMark(int entity, int ticks) {
        StasisFx.mark(entity, ticks);
    }

    @Override
    public void photonicGlow(int entity, boolean on) {
        PhotonicGlow.setGlowing(entity, on);
    }
}
