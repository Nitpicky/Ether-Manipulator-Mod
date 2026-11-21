package com.ethermod.client.event;

import com.ethermod.EtherMod;
import com.ethermod.client.beam.BeamBallRenderer;
import com.ethermod.client.beam.BeamRenderer;
import com.ethermod.registry.ModEntities;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

@EventBusSubscriber(modid = EtherMod.MODID, value = Dist.CLIENT)
public class ClientEvents {

    /** The K-screen turntable (GUI 3D is a picture in picture in 26.3). */
    @SubscribeEvent
    public static void onRegisterPip(net.neoforged.neoforge.client.event.RegisterPictureInPictureRenderersEvent event) {
        event.register(com.ethermod.client.KModelPip.State.class, com.ethermod.client.KModelPip::new);
    }

    /** Recoloured body textures and the upgrade screen's assets follow pack changes. */
    @SubscribeEvent
    public static void onReloadListeners(net.neoforged.neoforge.client.event.AddClientReloadListenersEvent event) {
        event.addListener(
                net.minecraft.resources.Identifier.fromNamespaceAndPath(EtherMod.MODID, "client_reload"),
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener)
                        manager -> com.ethermod.client.ClientReload.onReload());
    }

    @SubscribeEvent
    public static void onRegisterPipelines(
            net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent event) {
        com.ethermod.client.ModPipelines.register(event);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // the Mods list's Config button opens NeoForge's settings screen for both config files
        net.neoforged.fml.ModList.get().getModContainerById(EtherMod.MODID).ifPresent(c -> c.registerExtensionPoint(
                net.neoforged.neoforge.client.gui.IConfigScreenFactory.class,
                net.neoforged.neoforge.client.gui.ConfigurationScreen::new));
        NeoForge.EVENT_BUS.register(ClientInputHandler.class);
        com.ethermod.entity.SoulBlazeling.smokeEmitter = com.ethermod.client.BlazelingSmoke::emit;
        com.ethermod.beam.BeamBallEntity.clientRopeTick = com.ethermod.client.ClientMobility::ropeTick;
        NeoForge.EVENT_BUS.register(BeamRenderer.class);
        NeoForge.EVENT_BUS.register(com.ethermod.client.beam.MuzzleEvents.class);
        if (Boolean.getBoolean("ethermod.playtest")) {
            try {
                NeoForge.EVENT_BUS.register(
                        Class.forName("com.ethermod.client.PlaytestBootstrap"));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Playtest classes missing; run with -Pplaytest", e);
            }
        }
        NeoForge.EVENT_BUS.register(com.ethermod.client.beam.BeamAudio.class);
        NeoForge.EVENT_BUS.register(com.ethermod.client.ClientCacheReset.class);
        NeoForge.EVENT_BUS.register(com.ethermod.client.PhotonicGlow.class);
        NeoForge.EVENT_BUS.register(ManipulatorKeys.class);
        NeoForge.EVENT_BUS.register(com.ethermod.client.ModeWheel.class);
        NeoForge.EVENT_BUS.register(com.ethermod.client.ClientMobility.class);
        com.ethermod.trait.RapidsCurrents.clientLevel =
                () -> net.minecraft.client.Minecraft.getInstance().level;
        NeoForge.EVENT_BUS.register(com.ethermod.client.GravityRender.class);
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        ManipulatorKeys.register(event);
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(
            net.neoforged.neoforge.client.event.RegisterGuiLayersEvent event) {
        event.registerAbove(
                net.neoforged.neoforge.client.gui.VanillaGuiLayers.HOTBAR,
                net.minecraft.resources.Identifier.fromNamespaceAndPath(
                        EtherMod.MODID, "manipulator_hud"),
                com.ethermod.client.ManipulatorHud::render);
    }

    /** Stasis: every living creature's renderer gets the amber tint layer (client StasisFx). */
    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void stasisLayers(EntityRenderersEvent.AddLayers event) {
        for (var type : event.getEntityTypes())
            if (event.getRenderer(type) instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer r)
                r.addLayer(new com.ethermod.client.StasisFx.Layer(r));
        for (var skin : event.getSkins())
            if (event.getPlayerRenderer(skin) instanceof net.minecraft.client.renderer.entity.LivingEntityRenderer r)
                r.addLayer(new com.ethermod.client.StasisFx.Layer(r));
    }

    @SubscribeEvent
    public static void blazelingLayer(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(
                com.ethermod.client.BlazelingModel.LAYER,
                com.ethermod.client.BlazelingModel::layer);
    }

    @SubscribeEvent
    public static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.BEAM_BALL.get(), BeamBallRenderer::new);
        event.registerEntityRenderer(
                ModEntities.SOUL_BLAZELING.get(), com.ethermod.client.BlazelingRenderer::new);
        event.registerEntityRenderer(
                ModEntities.SOUL_BOLT.get(), com.ethermod.client.SoulBoltRenderer::new);
        event.registerEntityRenderer(
                ModEntities.LIGHT_DECOY.get(), com.ethermod.client.LightDecoyRenderer::new);
    }
}
