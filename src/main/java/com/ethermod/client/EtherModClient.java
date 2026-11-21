package com.ethermod.client;

import com.ethermod.EtherMod;
import com.ethermod.SideHooks;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;

/**
 * Client entry point: FML constructs it only on a client, alongside {@link EtherMod}. It installs
 * the client {@link SideHooks} before any item, renderer or payload needs them. Client event
 * listeners live in {@code client.event.ClientEvents}.
 */
@Mod(value = EtherMod.MODID, dist = Dist.CLIENT)
public final class EtherModClient {

    public EtherModClient(IEventBus modEventBus, ModContainer container) {
        SideHooks.install(new ClientSideHooks());
        // 26.3: Item.initializeClient is gone; the arm pose hooks are registered here.
        modEventBus.addListener(
                (net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent e) ->
                        e.registerItem(
                                ManipulatorClientExtensions.INSTANCE,
                                com.ethermod.registry.ModItems.ETHER_MANIPULATOR.get()));
    }
}
