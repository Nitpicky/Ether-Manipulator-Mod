package com.ethermod;

import com.ethermod.item.EtherManipulatorItem;
import com.ethermod.network.payload.ModulePayloads;

import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

import com.geckolib.animation.AnimationController;

/**
 * The one way common code reaches client-only code. This base class does nothing (a dedicated
 * server keeps it); the client entry point ({@code client.EtherModClient}) installs the client
 * version at mod construction. Common classes call {@code SideHooks.get().x()} and so never name a
 * client class, which keeps them loadable on a dedicated server.
 */
public class SideHooks {

    private static SideHooks instance = new SideHooks();

    public static SideHooks get() {
        return instance;
    }

    /** Client entry point only. */
    public static void install(SideHooks hooks) {
        instance = hooks;
    }

    protected SideHooks() {}

    /** The item's renderer, arm pose and hand transform (client); null on a server. */
    public IClientItemExtensions manipulatorExtensions() {
        return null;
    }

    /** The prong animation controller (client); null on a server, which animates nothing. */
    public AnimationController<EtherManipulatorItem> manipulatorController(EtherManipulatorItem item) {
        return null;
    }

    /** Client: the local player threw a Stasis Grenade (plays the throw animation). */
    public void grenadeThrow(Player player) {}

    // play-to-client payloads (the handlers only run on a client)
    public void waterCurrent(ModulePayloads.WaterCurrent p) {}

    public void soulFlame(ModulePayloads.SoulFlame p) {}

    public void gravity(int entity, int direction, long start) {}

    public void dryFire() {}

    public void stasisForce(int entity, float x, float y, float z) {}

    public void stasisMark(int entity, int ticks) {}

    public void photonicGlow(int entity, boolean on) {}
}
