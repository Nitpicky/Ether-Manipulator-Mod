package com.mattermod.event;

import com.mattermod.MatterMod;
import com.mattermod.beam.BeamBallRenderer;
import com.mattermod.beam.BeamRenderer;
import com.mattermod.registry.ModEntities;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;

@EventBusSubscriber(modid = MatterMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class ClientEvents {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        NeoForge.EVENT_BUS.register(ClientInputHandler.class);
        com.mattermod.entity.SoulBlazeling.smokeEmitter = com.mattermod.client.BlazelingSmoke::emit;
        NeoForge.EVENT_BUS.register(BeamRenderer.class);
        NeoForge.EVENT_BUS.register(com.mattermod.beam.MuzzleEvents.class);
        if (Boolean.getBoolean("mattermod.playtest")) {
            try {
                NeoForge.EVENT_BUS.register(
                        Class.forName("com.mattermod.client.PlaytestBootstrap"));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Playtest classes missing; run with -Pplaytest", e);
            }
        }
        NeoForge.EVENT_BUS.register(com.mattermod.beam.BeamAudio.class);
        NeoForge.EVENT_BUS.register(com.mattermod.client.ClientCacheReset.class);
        NeoForge.EVENT_BUS.register(ManipulatorKeys.class);
        NeoForge.EVENT_BUS.register(com.mattermod.client.ModeWheel.class);
        NeoForge.EVENT_BUS.register(com.mattermod.client.ClientMobility.class);
        NeoForge.EVENT_BUS.register(com.mattermod.client.AirwalkTiles.class);
        com.mattermod.trait.RapidsCurrents.clientLevel =
                () -> net.minecraft.client.Minecraft.getInstance().level;
        NeoForge.EVENT_BUS.register(com.mattermod.client.GravityRender.class);
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
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
                        MatterMod.MODID, "manipulator_hud"),
                com.mattermod.client.ManipulatorHud::render);
    }

    @SubscribeEvent
    public static void particles(
            net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(
                com.mattermod.registry.ModParticles.BASE.get(),
                sprites ->
                        (type, level, x, y, z, dx, dy, dz) ->
                                new com.mattermod.client.GeyserParticle(
                                        level, x, y, z, dx, dy, dz, sprites, 0));
        event.registerSpriteSet(
                com.mattermod.registry.ModParticles.PLUME.get(),
                sprites ->
                        (type, level, x, y, z, dx, dy, dz) ->
                                new com.mattermod.client.GeyserParticle(
                                        level, x, y, z, dx, dy, dz, sprites, 1));
        event.registerSpriteSet(
                com.mattermod.registry.ModParticles.POOF.get(),
                sprites ->
                        (type, level, x, y, z, dx, dy, dz) ->
                                new com.mattermod.client.GeyserParticle(
                                        level, x, y, z, dx, dy, dz, sprites, 2));
    }

    @SubscribeEvent
    public static void blazelingLayer(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(
                com.mattermod.client.BlazelingModel.LAYER,
                com.mattermod.client.BlazelingModel::layer);
    }

    @SubscribeEvent
    public static void onRegisterEntityRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.BEAM_BALL.get(), BeamBallRenderer::new);
        event.registerEntityRenderer(
                ModEntities.SOUL_BLAZELING.get(), com.mattermod.client.BlazelingRenderer::new);
        event.registerEntityRenderer(
                ModEntities.SOUL_BOLT.get(), com.mattermod.client.SoulBoltRenderer::new);
        event.registerEntityRenderer(
                ModEntities.LIGHT_DECOY.get(), com.mattermod.client.LightDecoyRenderer::new);
        event.registerEntityRenderer(
                ModEntities.REDSTONE_GOLEM.get(), com.mattermod.client.RedstoneGolemRenderer::new);
        event.registerEntityRenderer(
                ModEntities.MINISTROSITY.get(), com.mattermod.client.MinistrosityRenderer::new);
        event.registerEntityRenderer(
                ModEntities.REDSTONE_BOLT.get(),
                net.minecraft.client.renderer.entity.ThrownItemRenderer::new);
    }
}
