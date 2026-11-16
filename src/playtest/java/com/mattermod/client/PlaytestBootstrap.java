package com.mattermod.client;

import com.mattermod.manipulator.Manipulator;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Scripted playtest for CI. Only active when the environment variable {@code MATTERMOD_AUTOTEST} is
 * set (the playtest workflow sets it; normal games never do).
 *
 * <p>It creates a flat survival world, builds a small test wall, summons the Manipulator, then
 * mines, fights, charges and fires orbs in first and third person while taking screenshots, writes
 * {@code mattermod-autotest.txt} with PASS/FAIL checks, and quits.
 */
@OnlyIn(Dist.CLIENT)
public final class PlaytestBootstrap {

    private PlaytestBootstrap() {}

    public static final boolean ENABLED = System.getenv("MATTERMOD_AUTOTEST") != null;

    private static final String WORLD = "mm_autotest";
    private static boolean worldRequested;
    private static int t = -1;
    private static BlockPos base;
    private static net.minecraft.world.phys.Vec3 cloudReturn = net.minecraft.world.phys.Vec3.ZERO;
    private static final List<String> report = new ArrayList<>();
    private static int mineBrokeAt = -1;
    private static boolean done;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!ENABLED || done) return;
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.onboardAccessibility = false;

        if (mc.level == null || mc.player == null) {
            if (!worldRequested && mc.screen != null && mc.getOverlay() == null) {
                if (!(mc.screen instanceof TitleScreen)) {
                    mc.setScreen(new TitleScreen());
                    return;
                }
                worldRequested = true;
                createWorld(mc);
            }
            return;
        }
        if (mc.screen != null && t < 0) return; // still on the loading screen
        t++;
        try {
            step(mc);
        } catch (Throwable e) {
            log("FAIL exception at tick " + t + ": " + e);
            finish(mc);
        }
    }

    private static void createWorld(Minecraft mc) {
        if (Boolean.getBoolean("mattermod.uiKeepState")
                && Files.isDirectory(mc.gameDirectory.toPath().resolve("saves").resolve(WORLD))) {
            mc.createWorldOpenFlows().openWorld(WORLD, () -> mc.setScreen(new TitleScreen()));
            return;
        }

        LevelSettings settings =
                new LevelSettings(
                        WORLD,
                        GameType.SURVIVAL,
                        false,
                        Difficulty.PEACEFUL,
                        true,
                        new GameRules(),
                        WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows()
                .createFreshLevel(
                        WORLD,
                        settings,
                        new WorldOptions(1L, false, false),
                        access ->
                                access.registryOrThrow(Registries.WORLD_PRESET)
                                        .getHolderOrThrow(WorldPresets.FLAT)
                                        .value()
                                        .createWorldDimensions(),
                        new TitleScreen());
    }

    // =========================================================================
    // The script
    // =========================================================================

    private static void step(Minecraft mc) {
        if (Boolean.getBoolean("mattermod.uiProbe")) {
            UiProbe.step(mc);
            return;
        }
        int stopAfter = Integer.getInteger("mattermod.stopAfter", 0);
        if (stopAfter > 0 && t >= stopAfter) {
            mc.stop();
            return;
        }
        var opts = mc.options;
        if (t > 70 && t < 160) checkMined(mc);
        if (t > 432 && t < 476)
            mc.player.setYRot(160 + (t - 432) * 1.0f); // slow sweep across the wall
        if (t == 1) mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        switch (t) {
            case 40 ->
                    server(
                            mc,
                            (s, p) -> {
                                run(s, "gamerule sendCommandFeedback false");
                                run(s, "time set noon");
                                run(s, "weather clear");
                                run(s, "gamerule doDaylightCycle false");
                                run(s, "gamerule doMobSpawning false");
                                base = p.blockPosition();
                                int x = base.getX(), y = base.getY(), z = base.getZ();
                                // a 5x3 wall of stone 4 blocks north, ores in the middle row
                                run(
                                        s,
                                        "fill " + (x - 2) + " " + y + " " + (z - 4) + " " + (x + 2)
                                                + " " + (y + 2) + " " + (z - 4) + " stone");
                                run(
                                        s,
                                        "setblock "
                                                + (x - 1)
                                                + " "
                                                + (y + 1)
                                                + " "
                                                + (z - 4)
                                                + " coal_ore");
                                run(
                                        s,
                                        "setblock "
                                                + (x + 1)
                                                + " "
                                                + (y + 1)
                                                + " "
                                                + (z - 4)
                                                + " iron_ore");
                                // tall grass in the line of sight: preview and beam should pass
                                // through it
                                run(
                                        s,
                                        "setblock "
                                                + x
                                                + " "
                                                + y
                                                + " "
                                                + (z - 2)
                                                + " tall_grass[half=lower]");
                                run(
                                        s,
                                        "setblock "
                                                + x
                                                + " "
                                                + (y + 1)
                                                + " "
                                                + (z - 2)
                                                + " tall_grass[half=upper]");
                                // a target pig to the east
                                run(
                                        s,
                                        "summon pig "
                                                + (x + 5)
                                                + " "
                                                + y
                                                + " "
                                                + z
                                                + " {NoAI:1b,Invulnerable:1b}");
                                run(s, "tp @p " + (x + 0.5) + " " + y + " " + (z + 0.5) + " 180 0");
                                Manipulator.setSummoned(p, true);
                                // colour sources for the K screen swatches
                                run(s, "give @p emerald 2");
                                run(s, "give @p amethyst_shard 1");
                                run(s, "give @p lime_dye 2");
                                run(s, "give @p light_blue_dye 1");
                            });
            case 60 -> shot(mc, "01_idle_first_person");

            // ---- Mining the stone block straight ahead -------------------------
            case 70 -> opts.keyAttack.setDown(true);
            case 78 -> shot(mc, "02_mining_first_person");
            case 100 -> shot(mc, "03_mining_later");
            case 160 -> {
                opts.keyAttack.setDown(false);
                checkMined(mc);
                log(
                        mineBrokeAt > 0
                                ? "PASS mining: stone ahead broke after "
                                        + mineBrokeAt
                                        + " ticks of beam"
                                : "FAIL mining: stone ahead did not break in 90 ticks of beam");
                crystal(mc, false, "after mining");
                server(
                        mc,
                        (s, p) -> {
                            var at = new net.minecraft.world.phys.AABB(base).inflate(8);
                            boolean dropped =
                                    p.getInventory()
                                                    .contains(
                                                            new net.minecraft.world.item.ItemStack(
                                                                    net.minecraft.world.item.Items
                                                                            .COBBLESTONE))
                                            || !p.serverLevel()
                                                    .getEntitiesOfClass(
                                                            net.minecraft.world.entity.item
                                                                    .ItemEntity.class,
                                                            at,
                                                            e ->
                                                                    e.getItem()
                                                                            .is(
                                                                                    net.minecraft
                                                                                            .world
                                                                                            .item
                                                                                            .Items
                                                                                            .COBBLESTONE))
                                                    .isEmpty();
                            log(
                                    (dropped ? "PASS" : "FAIL")
                                            + " drops: mined stone gave cobblestone (empty hotbar"
                                            + " slot selected: "
                                            + p.getInventory().getSelected().isEmpty()
                                            + ")");
                        });
            }

            // ---- Third person, seen from the side ---------------------------------
            case 165 ->
                    server(
                            mc,
                            (s, p) -> {
                                int x = base.getX(), y = base.getY(), z = base.getZ();
                                run(s, "setblock " + x + " " + (y + 1) + " " + (z - 4) + " stone");
                                // an invisible camera 4 blocks to the east, looking west at the
                                // player
                                run(
                                        s,
                                        "summon armor_stand "
                                                + (x + 4.5)
                                                + " "
                                                + (y + 0.2)
                                                + " "
                                                + (z - 1.0)
                                                + " {Invisible:1b,NoGravity:1b,CustomName:'\"mmcam\"',Rotation:[90f,5f]}");
                            });
            case 175 -> {
                sideCamera(mc, true);
                opts.keyAttack.setDown(true);
            }
            case 185 -> shot(mc, "04_beam_side_view");
            case 205 -> shot(mc, "05_beam_side_view_later");
            case 210 -> {
                opts.keyAttack.setDown(false);
                sideCamera(mc, false);
            }

            // ---- Combat ramp on the pig -----------------------------------------
            case 220 -> {
                mc.player.setYRot(-90);
                mc.player.setXRot(8);
            }
            case 225 -> opts.keyAttack.setDown(true);
            case 245 -> shot(mc, "06_combat_level1");
            case 290 -> shot(mc, "07_combat_ramped");
            // the beam swung up into the clouds: effects must draw in front of them
            // lifted just under the cloud layer (y 192), aiming up through it
            case 291 ->
                    server(
                            mc,
                            (s, p) -> {
                                cloudReturn = p.position();
                                int x = p.getBlockX(), z = p.getBlockZ();
                                run(s, "setblock " + x + " 184 " + z + " glass");
                                run(s, "tp @p " + (x + 0.5) + " 185 " + (z + 0.5) + " -90 -35");
                            });
            case 301 -> shot(mc, "07b_beam_clouds");
            case 302 -> {
                opts.keyAttack.setDown(false);
                server(
                        mc,
                        (s, p) ->
                                run(
                                        s,
                                        "tp @p "
                                                + cloudReturn.x
                                                + " "
                                                + cloudReturn.y
                                                + " "
                                                + cloudReturn.z
                                                + " -90 8"));
            }

            // ---- Charge and fire an orb --------------------------------------------
            case 305 -> {
                mc.player.setYRot(180);
                mc.player.setXRot(0);
                opts.keyUse.setDown(true);
            }
            case 315 -> shot(mc, "08_orb_charge_phase1");
            case 345 -> shot(mc, "09_orb_charge_full");
            case 350 -> opts.keyUse.setDown(false);
            case 352 -> {
                shot(mc, "10_orb_fired");
                crystal(mc, true, "after an Energy orb");
            }
            case 360 -> {
                sideCamera(mc, true);
                opts.keyUse.setDown(true);
            }
            case 395 -> shot(mc, "11_orb_charge_side_view");
            case 396 -> {
                opts.keyUse.setDown(false);
            }
            case 398 -> shot(mc, "12_orb_fired_side_view");
            case 405 -> sideCamera(mc, false);

            // ---- Trait screen ----------------------------------------------------
            case 410 -> mc.setScreen(new TraitScreen());
            case 420 -> shot(mc, "13_trait_screen");
            case 421 -> {
                TraitScreen.debugTab(1);
                mc.setScreen(new TraitScreen());
            }
            case 423 -> shot(mc, "13b_colours_tab");
            case 424 -> {
                TraitScreen.debugTab(2);
                mc.setScreen(new TraitScreen());
            }
            case 426 -> shot(mc, "13c_loadouts_tab");
            case 427 -> {
                TraitScreen.debugTab(0);
                mc.setScreen(null);
            }

            // ---- Custom colours, and the glow remembering several blocks -----------
            case 428 ->
                    server(
                            mc,
                            (s, p) -> {
                                int slot = -1;
                                var inv = p.getInventory().items;
                                for (int i = 0; i < inv.size(); i++)
                                    if (inv.get(i).is(net.minecraft.world.item.Items.EMERALD)) {
                                        slot = i;
                                        break;
                                    }
                                // the same path the K screen's swatch uses
                                ItemStack mm = Manipulator.stack(p);
                                if (slot >= 0)
                                    com.mattermod.item.ManipulatorColors.setBeam(
                                            mm,
                                            com.mattermod.item.ManipulatorColors.beamFrom(
                                                    inv.get(slot)));
                                com.mattermod.item.ManipulatorColors.setBody(
                                        mm,
                                        net.minecraft.world.item.DyeColor.LIGHT_BLUE
                                                        .getTextureDiffuseColor()
                                                & 0xFFFFFF);
                                Manipulator.sync(p);
                                int x = base.getX(), y = base.getY(), z = base.getZ();
                                run(
                                        s,
                                        "fill " + (x - 2) + " " + y + " " + (z - 4) + " " + (x + 2)
                                                + " " + (y + 2) + " " + (z - 4) + " stone");
                            });
            case 432 -> {
                mc.player.setYRot(160);
                mc.player.setXRot(8);
                opts.keyAttack.setDown(true);
            }
            case 476 -> shot(mc, "15_custom_colour_sweep");
            case 478 -> {
                opts.keyAttack.setDown(false);
                var held = Manipulator.stack(mc.player);
                int c = com.mattermod.item.ManipulatorColors.beam(held);
                float[] b = com.mattermod.item.ManipulatorColors.beamRgba(held);
                boolean orange =
                        Math.round(b[0] * 255) == 0xE9
                                && Math.round(b[1] * 255) == 0x9F
                                && Math.round(b[2] * 255) == 0x20;
                log(
                        (c == com.mattermod.item.OrbMode.get(held).color && orange
                                        ? "PASS"
                                        : "FAIL")
                                + " colour: orbs follow the mode ("
                                + Integer.toHexString(c)
                                + "), the beam is Plasmic orange ("
                                + orange
                                + ")");
            }
            case 482 -> {
                mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            }
            case 486 -> shot(mc, "16_custom_colour_model");
            case 488 -> mc.options.setCameraType(CameraType.FIRST_PERSON);

            // ---- Sneak + beam: utility actions (till the grass in front) ---------
            case 489 ->
                    server(
                            mc,
                            (s, p) -> {
                                int x = base.getX(), y = base.getY(), z = base.getZ();
                                run(s, "setblock " + x + " " + (y + 1) + " " + (z - 2) + " air");
                                run(s, "setblock " + x + " " + y + " " + (z - 2) + " air");
                                run(
                                        s,
                                        "setblock "
                                                + x
                                                + " "
                                                + (y - 1)
                                                + " "
                                                + (z - 2)
                                                + " grass_block");
                            });
            case 492 -> {
                opts.keyShift.setDown(true);
                mc.player.setYRot(180);
                mc.player.setXRot(34);
            }
            case 496 -> opts.keyAttack.setDown(true);
            case 520 -> shot(mc, "17_sneak_beam");
            case 524 -> {
                BlockPos till = new BlockPos(base.getX(), base.getY() - 1, base.getZ() - 2);
                var st = mc.level.getBlockState(till);
                log(
                        (st.is(net.minecraft.world.level.block.Blocks.DIRT_PATH) ? "PASS" : "FAIL")
                                + " sneak beam, no water near: grass ahead is now "
                                + st.getBlock().getDescriptionId()
                                + " (beamActive="
                                + com.mattermod.beam.BeamRenderState.beamActive
                                + ", sneaking="
                                + mc.player.isShiftKeyDown()
                                + ", hit="
                                + mc.hitResult
                                + ")");
                opts.keyAttack.setDown(false);
                opts.keyShift.setDown(false);
            }
            // ---- Same again with water beside it: now it tills ---------------
            case 527 ->
                    server(
                            mc,
                            (s, p) -> {
                                int x = base.getX(), y = base.getY(), z = base.getZ();
                                run(
                                        s,
                                        "fill "
                                                + (x - 1)
                                                + " "
                                                + (y - 1)
                                                + " "
                                                + (z - 3)
                                                + " "
                                                + (x + 1)
                                                + " "
                                                + (y - 1)
                                                + " "
                                                + (z - 1)
                                                + " grass_block");
                                run(
                                        s,
                                        "setblock "
                                                + (x + 3)
                                                + " "
                                                + (y - 1)
                                                + " "
                                                + (z - 2)
                                                + " water");
                            });
            case 530 -> {
                opts.keyShift.setDown(true);
                mc.player.setYRot(180);
                mc.player.setXRot(34);
            }
            case 532 -> opts.keyAttack.setDown(true);
            case 552 -> {
                BlockPos till = new BlockPos(base.getX(), base.getY() - 1, base.getZ() - 2);
                var st = mc.level.getBlockState(till);
                log(
                        (st.is(net.minecraft.world.level.block.Blocks.FARMLAND) ? "PASS" : "FAIL")
                                + " sneak beam, water near: grass ahead is now "
                                + st.getBlock().getDescriptionId());
                opts.keyAttack.setDown(false);
                opts.keyShift.setDown(false);
            }

            // ---- Other items still swing when the Manipulator is put away ----------
            case 560 ->
                    server(
                            mc,
                            (s, p) -> {
                                Manipulator.setSummoned(p, false);
                                run(s, "item replace entity @p hotbar.0 with iron_sword");
                            });
            case 570 -> mc.player.swing(InteractionHand.MAIN_HAND);
            case 572 -> {
                boolean swinging = mc.player.swinging || mc.player.getAttackAnim(0) > 0;
                log(
                        (swinging ? "PASS" : "FAIL")
                                + " swing: a sword still swings with the Manipulator put away");
                shot(mc, "14_sword_swing");
            }

            // ---- Beam across a water surface, seen from under the water ------------
            case 576 ->
                    server(
                            mc,
                            (s, p) -> {
                                Manipulator.setSummoned(p, true);
                                int x = base.getX(), y = base.getY(), z = base.getZ();
                                run(
                                        s,
                                        "fill " + (x - 3) + " " + (y - 2) + " " + (z - 3) + " "
                                                + (x + 3) + " " + y + " " + (z + 6) + " water");
                            });
            case 582 -> {
                mc.player.setYRot(0);
                mc.player.setXRot(-25);
                mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
                opts.keyAttack.setDown(true);
            }
            case 602 -> shot(mc, "18_beam_water_front");
            case 603 -> mc.options.setCameraType(CameraType.FIRST_PERSON);
            case 608 -> shot(mc, "19_beam_water_first_person");
            case 610 -> opts.keyAttack.setDown(false);

            // ---- Orb modes: each one's basic job -------------------------------------
            case 618 ->
                    server(
                            mc,
                            (s, p) -> {
                                int bx = base.getX() + 30, y = base.getY(), bz = base.getZ();
                                run(
                                        s,
                                        "fill "
                                                + (bx - 4)
                                                + " "
                                                + (y - 1)
                                                + " "
                                                + (bz - 2)
                                                + " "
                                                + (bx + 4)
                                                + " "
                                                + (y - 1)
                                                + " "
                                                + (bz + 24)
                                                + " grass_block");
                                run(
                                        s,
                                        "fill " + (bx - 4) + " " + y + " " + (bz - 2) + " "
                                                + (bx + 4) + " " + (y + 8) + " " + (bz + 24)
                                                + " air");
                                run(
                                        s,
                                        "fill " + (bx - 1) + " " + (y - 4) + " " + (bz + 3) + " "
                                                + (bx + 1) + " " + (y - 1) + " " + (bz + 12)
                                                + " air");
                                run(s, "tp @p " + bx + ".5 " + y + " " + bz + ".5 0 0");
                                Manipulator.setSummoned(p, true);
                                orbMode(p, com.mattermod.item.OrbMode.FROST);
                            });
            case 624 -> {
                mc.player.setYRot(0);
                mc.player.setXRot(0);
                opts.keyUse.setDown(true);
            }
            case 660 -> shot(mc, "20a_frost_charging");
            case 674 -> opts.keyUse.setDown(false); // full charge: a 16-block bridge
            case 680 -> mc.player.setXRot(35);
            case 686 -> {
                int bx = base.getX() + 30, y = base.getY(), bz = base.getZ(), ice = 0;
                for (int z = bz + 3; z <= bz + 12; z++)
                    if (mc.level
                            .getBlockState(new BlockPos(bx, y - 1, z))
                            .is(net.minecraft.world.level.block.Blocks.PACKED_ICE)) ice++;
                log(
                        (ice >= 6 ? "PASS" : "FAIL")
                                + " frost: charged shard laid an ice bridge over the trench ("
                                + ice
                                + "/10 blocks)");
                shot(mc, "20_frost_bridge");
            }
            // Water: a left-click tap is a bucket (the blob is Hydraulic's left click)
            case 690 -> server(mc, (s, p) -> orbMode(p, com.mattermod.item.OrbMode.WATER));
            case 694 -> {
                mc.player.setYRot(25);
                mc.player.setXRot(15);
                opts.keyAttack.setDown(true);
            }
            case 696 -> opts.keyAttack.setDown(false);
            case 720 -> {
                int bx = base.getX() + 30, y = base.getY(), bz = base.getZ();
                boolean water = false;
                for (BlockPos q : BlockPos.betweenClosed(bx - 4, y - 4, bz, bx + 4, y + 1, bz + 20))
                    if (mc.level.getFluidState(q).isSource()
                            && mc.level.getFluidState(q).is(net.minecraft.tags.FluidTags.WATER))
                        water = true;
                log(
                        (water ? "PASS" : "FAIL")
                                + " water: a tap placed a water source like a bucket");
                shot(mc, "21_water_tap");
            }
            // Vacuum mode is gone: instead check the crystal takes the mode colour (Lava here)
            case 722 -> server(mc, (s, p) -> orbMode(p, com.mattermod.item.OrbMode.LAVA));
            case 730 -> {
                mc.player.setXRot(10);
                int c = com.mattermod.item.ManipulatorColors.beam(Manipulator.stack(mc.player));
                log(
                        (c == com.mattermod.item.OrbMode.LAVA.color ? "PASS" : "FAIL")
                                + " colour: crystal switched to the Lava colour ("
                                + Integer.toHexString(c)
                                + ")");
            }
            case 740 -> shot(mc, "22_lava_mode_crystal");
            // Growth: charged pollen cloud
            case 774 -> server(mc, (s, p) -> orbMode(p, com.mattermod.item.OrbMode.GROWTH));
            case 778 -> {
                mc.player.setYRot(0);
                mc.player.setXRot(10);
                opts.keyUse.setDown(true);
            }
            case 815 -> shot(mc, "23a_growth_charging");
            case 828 -> opts.keyUse.setDown(false);
            case 850 -> shot(mc, "23_pollen_cloud");
            // Light: bouncing ball
            case 856 -> server(mc, (s, p) -> orbMode(p, com.mattermod.item.OrbMode.LIGHT));
            case 860 -> {
                mc.player.setYRot(-20);
                mc.player.setXRot(25);
                opts.keyUse.setDown(true);
            }
            case 910 -> opts.keyUse.setDown(false);
            case 925 -> shot(mc, "24_light_bounce");
            // Tether: hook a block above and get reeled up
            case 930 ->
                    server(
                            mc,
                            (s, p) -> {
                                int bx = base.getX() + 30, y = base.getY(), bz = base.getZ();
                                run(
                                        s,
                                        "fill " + (bx - 1) + " " + (y + 7) + " " + (bz + 7) + " "
                                                + (bx + 1) + " " + (y + 7) + " " + (bz + 9)
                                                + " stone");
                                orbMode(p, com.mattermod.item.OrbMode.TETHER);
                                startY = p.getY();
                            });
            // left click fires the hook, holding right click reels
            case 934 -> {
                mc.player.setYRot(0);
                mc.player.setXRot(-38);
                opts.keyAttack.setDown(true);
            }
            case 936 -> opts.keyAttack.setDown(false);
            case 938 -> opts.keyUse.setDown(true);
            case 944 -> {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
            case 948 -> shot(mc, "25_tether_rope");
            case 956 -> {
                log(
                        (mc.player.getY() > startY + 1.5 ? "PASS" : "FAIL")
                                + " tether: the hook reeled the player up ("
                                + String.format("%.1f", mc.player.getY() - startY)
                                + " blocks)");
                opts.keyUse.setDown(false);
                mc.options.setCameraType(CameraType.FIRST_PERSON);
            }
            // ---- Left clicks of the other modes ---------------------------------------
            // Pneumatic: a blast grenade latches on a pig and bursts
            case 960 ->
                    server(
                            mc,
                            (s, p) -> {
                                int bx = base.getX() + 60, y = base.getY(), bz = base.getZ();
                                run(
                                        s,
                                        "fill " + (bx - 6) + " " + (y - 1) + " " + (bz - 2) + " "
                                                + (bx + 6) + " " + (y - 1) + " " + (bz + 20)
                                                + " stone");
                                run(
                                        s,
                                        "fill " + (bx - 6) + " " + y + " " + (bz - 2) + " "
                                                + (bx + 6) + " " + (y + 6) + " " + (bz + 20)
                                                + " air");
                                run(s, "tp @p " + bx + ".5 " + y + " " + bz + ".5 0 0");
                                // a pig that stands still (the grenade only has to latch on and burst)
                                run(s, "summon pig " + bx + ".5 " + y + " " + (bz + 6) + ".5 {NoAI:1b}");
                                orbMode(p, com.mattermod.item.OrbMode.GUST);
                                bursts = com.mattermod.beam.GustGrenade.bursts;
                            });
            case 966 -> {
                mc.player.setYRot(0);
                mc.player.setXRot(11);
            }
            case 968 -> opts.keyAttack.setDown(true);
            case 970 -> opts.keyAttack.setDown(false);
            case 978 -> shot(mc, "26_gust_grenade_latched"); // +2: grenade wind-up
            case 1006 -> shot(mc, "26b_gust_grenade_burst");
            case 1012 ->
                    server(
                            mc,
                            (s, p) -> {
                                var pigs = pigs(p);
                                boolean hurt =
                                        pigs.stream().anyMatch(g -> g.getHealth() < g.getMaxHealth());
                                log(
                                        (com.mattermod.beam.GustGrenade.bursts > bursts && hurt
                                                        ? "PASS"
                                                        : "FAIL")
                                                + " gust: left click threw a grenade that burst"
                                                + " (bursts "
                                                + (com.mattermod.beam.GustGrenade.bursts - bursts)
                                                + ", pig hurt "
                                                + hurt
                                                + ")");
                                run(s, "kill @e[type=pig]");
                            });
            // Gravitic: left click hooks a pig, a right click tap reels it all the way in
            case 1015 ->
                    server(
                            mc,
                            (s, p) -> {
                                int bx = base.getX() + 60, y = base.getY(), bz = base.getZ();
                                run(
                                        s,
                                        "summon pig " + bx + ".5 " + y + " " + (bz + 12) + ".5");
                                // a NoAI pig never moves (no travel at all), so the reel could
                                // not pull it; Slowness 255 drags every pull down to a third too, so
                                // a mildly slowed pig: it barely wanders, and the reel moves it freely
                                run(s, "effect give @e[type=pig] minecraft:slowness 60 3 true");
                                orbMode(p, com.mattermod.item.OrbMode.TETHER);
                            });
            // aim right at the pig, wherever it wandered (a fixed pitch landed the hook in the grass, reeling the player)
            case 1020, 1022 -> {
                aimAtPig(mc);
                if (t == 1022) opts.keyAttack.setDown(true);
            }
            case 1024 -> opts.keyAttack.setDown(false);
            case 1030 -> opts.keyUse.setDown(true);
            case 1032 -> opts.keyUse.setDown(false);
            case 1040 -> shot(mc, "27_tether_pull");
            // the hook still has to fly 12 blocks and clamp before it reels: sample the pig's distance as it comes
            case 1034, 1038, 1042, 1046, 1050 ->
                    server(
                            mc,
                            (s, p) -> {
                                for (var pig : pigs(p)) {
                                    tetherClosest = Math.min(tetherClosest, pig.distanceTo(p));
                                    var hook = com.mattermod.beam.OrbTether.activeHook(p);
                                    log("tether pig v=" + String.format("%.2f", pig.getDeltaMovement().horizontalDistance())
                                            + " onGround=" + pig.onGround() + " hook=" + (hook == null ? "none" : hook.debugTether()));
                                }
                                log("tether pig at " + String.format("%.1f", tetherClosest));
                            });
            case 1054 ->
                    server(
                            mc,
                            (s, p) -> {
                                double far = tetherClosest, moved = 0;
                                var spawn = new net.minecraft.world.phys.Vec3(base.getX() + 60.5, base.getY(), base.getZ() + 12.5);
                                for (var pig : pigs(p)) {
                                    far = Math.min(far, pig.distanceTo(p));
                                    moved = Math.max(moved, pig.position().subtract(spawn).horizontalDistance());
                                }
                                // the pig itself has to come (the player being reeled to a hook in the ground also closes the gap)
                                log(
                                        (far < 8 && moved > 3 ? "PASS" : "FAIL")
                                                + " tether: a right click tap reeled the hooked pig from 12 to "
                                                + String.format("%.1f", far)
                                                + " blocks (pig pulled " + String.format("%.1f", moved) + ")");
                                run(s, "kill @e[type=pig]");
                            });
            // Photonic: the Beacon lights where you aim while held, and mines nothing
            case 1055 ->
                    server(
                            mc,
                            (s, p) -> {
                                int bx = base.getX() + 60, y = base.getY(), bz = base.getZ();
                                run(s, "setblock " + bx + " " + y + " " + (bz + 8) + " stone");
                                run(s, "time set 18000");
                                orbMode(p, com.mattermod.item.OrbMode.LIGHT);
                            });
            case 1058 -> {
                lightsBefore = lightSpots(mc); // lights already there (the Light orb's earlier bounces) are not the Beacon's
                mc.player.setYRot(0);
                mc.player.setXRot(8);
            }
            case 1060 -> opts.keyAttack.setDown(true);
            case 1080 -> {
                shot(mc, "28_light_beacon");
                int bx = base.getX() + 60, y = base.getY(), bz = base.getZ();
                log(
                        (lights(mc) > 0 ? "PASS" : "FAIL")
                                + " light: the Beacon lights where it lands ("
                                + lights(mc)
                                + " light blocks)");
                boolean stone =
                        mc.level
                                .getBlockState(new BlockPos(bx, y, bz + 8))
                                .is(net.minecraft.world.level.block.Blocks.STONE);
                log(
                        (stone ? "PASS" : "FAIL")
                                + " mining is Beam-only: 20 ticks of left click in Light mode"
                                + " left the stone");
            }
            case 1100 -> opts.keyAttack.setDown(false);
            case 1113 -> {
                // the client hears about the removed light blocks a few ticks late
                var left = lightSpots(mc);
                left.removeAll(lightsBefore);
                // the Photonic badge's own glow follows the player (a level 10 light over the head): not the Beacon's
                BlockPos head = mc.player.blockPosition();
                left.removeIf(q -> Math.abs(q.getX() - head.getX()) <= 1 && Math.abs(q.getZ() - head.getZ()) <= 1
                        && q.getY() - head.getY() >= 0 && q.getY() - head.getY() <= 2);
                log(
                        (left.isEmpty() ? "PASS" : "FAIL")
                                + " light: the Beacon's light goes with it ("
                                + left.size()
                                + " left " + left + ", " + lightsBefore.size() + " were there before)");
                var leftover = new java.util.ArrayList<>(left);
                server(mc, (s, p) -> {
                    for (var q : leftover)
                        log("light leftover " + q + ": server has " + s.overworld().getBlockState(q));
                });
                server(mc, (s, p) -> run(s, "time set 6000"));
            }
            // Botanic: left click harvests the ripe crops around the aim
            case 1115 ->
                    server(
                            mc,
                            (s, p) -> {
                                int bx = base.getX() + 60, y = base.getY(), bz = base.getZ();
                                run(
                                        s,
                                        "fill " + (bx - 1) + " " + (y - 1) + " " + (bz + 2) + " "
                                                + (bx + 1) + " " + (y - 1) + " " + (bz + 4)
                                                + " farmland");
                                run(
                                        s,
                                        "fill " + (bx - 1) + " " + y + " " + (bz + 2) + " "
                                                + (bx + 1) + " " + y + " " + (bz + 4)
                                                + " wheat[age=7]");
                                orbMode(p, com.mattermod.item.OrbMode.GROWTH);
                                p.teleportTo(bx + .5, y, bz + .5);
                                harvested = com.mattermod.beam.LeftClick.harvested;
                            });
            case 1118 -> {
                mc.player.setYRot(0);
                mc.player.setXRot(12); // the middle wheat, 3 blocks ahead (22 hit the first row)
            }
            case 1120 -> opts.keyAttack.setDown(true);
            case 1122 -> opts.keyAttack.setDown(false);
            case 1126 -> shot(mc, "29_harvest");
            case 1135 ->
                    server(
                            mc,
                            (s, p) -> {
                                int got = com.mattermod.beam.LeftClick.harvested - harvested;
                                log(
                                        (got >= 9 ? "PASS" : "FAIL")
                                                + " growth: left click harvested "
                                                + got
                                                + " of 9 ripe wheat");
                            });
            // Tectonic: left click grabs a block, right click throws it
            case 1155 ->
                    server(
                            mc,
                            (s, p) -> {
                                int bx = base.getX() + 60, y = base.getY(), bz = base.getZ();
                                // a clear row to a single stone 3 blocks ahead, standing at the
                                // start of the row (the harvest step may have left crops)
                                run(
                                        s,
                                        "fill " + (bx - 1) + " " + y + " " + (bz + 1) + " "
                                                + (bx + 1) + " " + (y + 2) + " " + (bz + 4)
                                                + " air");
                                run(s, "setblock " + bx + " " + y + " " + (bz + 3) + " stone");
                                orbMode(p, com.mattermod.item.OrbMode.MASS);
                                p.teleportTo(bx + .5, y, bz + .5);
                            });
            case 1158 -> {
                mc.player.setYRot(0);
                mc.player.setXRot(20);
            }
            case 1160 -> opts.keyAttack.setDown(true);
            case 1163 -> opts.keyAttack.setDown(false);
            case 1170 ->
                    server(
                            mc,
                            (s, p) -> {
                                int bx = base.getX() + 60, y = base.getY(), bz = base.getZ();
                                boolean gone =
                                        p.serverLevel()
                                                .getBlockState(new BlockPos(bx, y, bz + 3))
                                                .isAir();
                                log(
                                        (com.mattermod.beam.MassDriver.holding(p) && gone
                                                        ? "PASS"
                                                        : "FAIL")
                                                + " mass driver: left click lifted the stone");
                            });
            case 1172 -> {
                mc.player.setXRot(-5);
                opts.keyUse.setDown(true);
            }
            case 1195 -> opts.keyUse.setDown(false);
            case 1197 -> shot(mc, "30_mass_throw");
            case 1205 ->
                    server(
                            mc,
                            (s, p) ->
                                    log(
                                            (!com.mattermod.beam.MassDriver.holding(p)
                                                            ? "PASS"
                                                            : "FAIL")
                                                    + " mass driver: right click threw the"
                                                    + " carried stone"));
            // Mechatronic: summon the Redstone Golem, order a sweep, fire the Dispenser, recall
            case 1210 ->
                    server(
                            mc,
                            (s, p) -> {
                                int bx = base.getX() + 84, y = base.getY(), bz = base.getZ();
                                run(s, "fill " + (bx - 8) + " " + y + " " + (bz - 6) + " " + (bx + 8)
                                        + " " + (y + 6) + " " + (bz + 16) + " air");
                                run(s, "fill " + (bx - 8) + " " + (y - 1) + " " + (bz - 6) + " "
                                        + (bx + 8) + " " + (y - 1) + " " + (bz + 16) + " grass_block");
                                run(s, "difficulty easy");
                                orbMode(p, com.mattermod.item.OrbMode.GOLEM);
                                ItemStack mm = Manipulator.stack(p);
                                int slot = com.mattermod.trait.TraitBuild.slotOf(
                                        mm, com.mattermod.trait.Traits.MECHATRONIC);
                                com.mattermod.trait.TraitBuild.setPick(mm, slot, 0, "mechatronic.dispenser_arm");
                                com.mattermod.trait.TraitBuild.setPick(mm, slot, 1, "mechatronic.ministrosities");
                                com.mattermod.trait.TraitBuild.setPick(mm, slot, 2, "mechatronic.rocket_boots");
                                Manipulator.sync(p);
                                p.teleportTo(bx + .5, y, bz + .5);
                            });
            case 1213 -> {
                mc.player.setYRot(-90);
                mc.player.setXRot(30);
            }
            case 1216 -> opts.keyUse.setDown(true);
            case 1218 -> opts.keyUse.setDown(false);
            case 1225 ->
                    server(
                            mc,
                            (s, p) -> {
                                var g = com.mattermod.entity.GolemControl.golem(p);
                                log((g != null ? "PASS" : "FAIL") + " golem: right click summoned the Redstone Golem");
                                log((g != null && g.hasMinis() && g.hasRockets() && g.hasSaddle() ? "PASS" : "FAIL")
                                        + " golem: Ministrosities, Rocket Boots and Saddle reached the Golem");
                            });
            case 1240 -> shot(mc, "31_golem_summon");
            case 1275 -> shot(mc, "32_golem_idle");
            case 1278 ->
                    server(
                            mc,
                            (s, p) -> {
                                int bx = base.getX() + 84, y = base.getY(), bz = base.getZ();
                                run(s, "summon husk " + bx + " " + y + " " + (bz + 5)
                                        + " {NoAI:1b,Tags:[\"golem_t1\"]}");
                                run(s, "summon husk " + bx + " " + y + " " + (bz + 11)
                                        + " {NoAI:1b,Tags:[\"golem_t2\"]}");
                            });
            case 1280 -> {
                mc.player.setYRot(0);
                mc.player.setXRot(10);
            }
            case 1283 -> opts.keyAttack.setDown(true);
            case 1285 -> opts.keyAttack.setDown(false);
            case 1287 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        log("golem state t1287: " + (g == null ? "none" : g.debugState()));
                    });
            case 1293 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        log("golem state t1293: " + (g == null ? "none" : g.debugState()));
                    });
            case 1310 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        log("golem state t1310: " + (g == null ? "none" : g.debugState()));
                    });
            case 1345 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        log("golem state t1345: " + (g == null ? "none" : g.debugState()));
                    });
            case 1365 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        log("golem state t1365: " + (g == null ? "none" : g.debugState()));
                    });
            case 1375 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        log("golem state t1375: " + (g == null ? "none" : g.debugState()));
                    });
            case 1319 -> shot(mc, "33_golem_sweep");
            case 1332 ->
                    server(
                            mc,
                            (s, p) -> {
                                var h = husk(p, "golem_t1");
                                log((h == null || h.getHealth() < h.getMaxHealth() ? "PASS" : "FAIL")
                                        + " golem: attack order hit the target");
                                if (h != null) h.discard();
                            });
            case 1322 -> mc.player.setXRot(4);
            case 1336 -> opts.keyUse.setDown(true);
            case 1338 -> opts.keyUse.setDown(false);
            case 1366 -> shot(mc, "34_golem_dispenser");
            case 1376 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        if (g != null) log("golem overheat: " + g.debugOverheat(p));
                    });
            case 1434, 1438 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        log("golem minis at t" + t + ": " + (g == null ? "no golem" : com.mattermod.entity.Ministrosity.of(g).size()));
                    });
            // the overheat is a state: nothing else while the generator blows, its time starts after
            case 1420 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        boolean ok = g != null && g.venting() && g.overheated() && !g.sweep(null);
                        log((ok ? "PASS" : "FAIL") + " golem: blowing its generator it does nothing else (venting "
                                + (g != null && g.venting()) + ")");
                    });
            case 1463 ->   // the dispenser runs to 1413, then the generator blows for 43 ticks
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        boolean ok = g != null && !g.venting() && g.overheated();
                        log((ok ? "PASS" : "FAIL") + " golem: back in action and still overheated after the animation");
                    });
            // Primal Rage: Supercharged arcs chain through a pack, the Cube Field stops a shot
            case 1439 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        if (g == null) return;
                        double[][] at = {{0, 3.5}, {1.5, 5.5}, {-1.5, 7}};
                        for (double[] o : at)
                            run(s, String.format(java.util.Locale.ROOT, "summon husk %.2f %.2f %.2f {NoAI:1b,Tags:[\"golem_c\"]}",
                                    g.getX() + o[0], g.getY(), g.getZ() + o[1]));
                    });
            case 1440 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        var hs = p.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.monster.Husk.class,
                                p.getBoundingBox().inflate(40), h -> h.getTags().contains("golem_c"));
                        hs.sort(java.util.Comparator.comparingDouble(h -> g == null ? 0 : h.distanceToSqr(g)));
                        int jumps = g == null || hs.isEmpty() ? 0 : g.chainArcs(p, hs.get(0));
                        log((jumps >= 2 ? "PASS" : "FAIL") + " golem: Supercharged arc chained through " + jumps + " more husks (want 2)");
                    });
            // the server can lag the client by many ticks here: the arrow starts just outside the wall, crossing at once
            case 1441 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        if (g != null)
                            run(s, String.format(java.util.Locale.ROOT, "summon arrow %.2f %.2f %.2f {Motion:[-1.5d,0.0d,0.0d],Tags:[\"fieldtest\"]}",
                                    g.getX() + 4.2, g.getY() + 2, g.getZ() + 2.5));
                    });
            case 1442 -> log((com.mattermod.client.GolemFx.liveChains() > 0 ? "PASS" : "FAIL")
                    + " golem: the client drew the arc chain (" + com.mattermod.client.GolemFx.liveChains() + " live)");
            case 1443 -> shot(mc, "35b_golem_arcs_field");
            case 1444 -> shot(mc, "35_ministrosities");
            case 1445 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        int n = g == null ? 0 : com.mattermod.entity.Ministrosity.of(g).size();
                        log((n == 3 ? "PASS" : "FAIL") + " golem: an overheat built " + n + " Ministrosities (want 3)");
                    });
            case 1446 ->
                    server(
                            mc,
                            (s, p) -> {
                                var h = husk(p, "golem_t2");
                                log((h == null || h.getHealth() < h.getMaxHealth() ? "PASS" : "FAIL")
                                        + " golem: Dispenser Arm bolts hit the far target");
                                if (h != null) h.discard();
                            });
            case 1447 -> server(mc, (s, p) -> {
                checkGemLoot(s, p);
                checkAdvancements(s, p);
                run(s, "kill @e[type=husk,tag=golem_c]");
                var g = com.mattermod.entity.GolemControl.golem(p);
                if (g == null) return;
                log("golem field: overheated=" + g.overheated() + " blocks=" + g.fieldBlocks);
            });
            case 1452 ->
                    server(mc, (s, p) -> {
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        var arrows = p.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.projectile.AbstractArrow.class,
                                p.getBoundingBox().inflate(60), a -> a.getTags().contains("fieldtest"));
                        boolean stopped = g != null && g.fieldBlocks > 0 && arrows.isEmpty();
                        log((stopped ? "PASS" : "FAIL") + " golem: the Cube Field stopped the arrow (blocks "
                                + (g == null ? -1 : g.fieldBlocks) + ", arrows left " + arrows.size() + ")");
                        arrows.forEach(net.minecraft.world.entity.Entity::discard);
                    });
            case 1453 -> {
                var g = mc.level.getEntitiesOfClass(com.mattermod.entity.RedstoneGolem.class, mc.player.getBoundingBox().inflate(40));
                String st = g.isEmpty() ? "no golem" : com.mattermod.client.GolemFx.debugField(g.get(0));
                log((st.contains("totalHits=") && !st.contains("totalHits=0") ? "PASS" : "FAIL") + " golem: the field is drawn and showed the hit (" + st + ")");
            }
            case 1449 -> server(mc, (s, p) -> checkGemGuarantee(s, p, "desert_pyramid", "atenite", false));
            case 1451 -> server(mc, (s, p) -> checkGemGuarantee(s, p, "jungle_pyramid", "quetzalite", true));
            case 1454 ->
                    server(
                            mc,
                            (s, p) -> {
                                int bx = base.getX() + 84, y = base.getY(), bz = base.getZ();
                                var g = com.mattermod.entity.GolemControl.golem(p);
                                if (g == null) return;
                                for (var m : com.mattermod.entity.Ministrosity.of(g)) m.discard();
                                g.teleportTo(bx + .5, y, bz + 3.5);
                                g.setYRot(0);
                                g.setYBodyRot(0);
                                p.startRiding(g, true);
                                run(s, "kill @e[type=armor_stand,name=mmcam]");
                                run(s, "summon armor_stand " + (bx + 6.5) + " " + (y - 0.4) + " " + (bz + 3.5)
                                        + " {Invisible:1b,NoGravity:1b,CustomName:'\"mmcam\"',Rotation:[90f,8f]}");
                            });
            case 1460 -> sideCamera(mc, true);
            case 1466 -> {
                var v = mc.player.getVehicle();
                log("golem seat: player feet " + (v == null ? "not riding" : String.format("%.3f", mc.player.getY() - v.getY()))
                        + " above the Golem (seat " + String.format("%.3f", com.mattermod.entity.RedstoneGolem.SEAT_Y)
                        + ", animated seat " + (v instanceof com.mattermod.entity.RedstoneGolem rg && rg.seatOffset != null ? String.format("%.3f", rg.seatOffset.y) : "none")
                        + ", vehicle attachment " + String.format("%.3f", v == null ? 0 : mc.player.getVehicleAttachmentPoint(v).y) + ")");
                double feet = v == null ? 9 : mc.player.getY() - v.getY();
                log((Math.abs(feet - (com.mattermod.entity.RedstoneGolem.SEAT_Y - .6)) < .3 ? "PASS" : "FAIL")
                        + " golem: the rider sits on the seat (feet " + String.format("%.2f", feet) + ")");
            }
            case 1468 -> shot(mc, "36_golem_ride");
            case 1470 -> sideCamera(mc, false);
            // the charged jump: hold Jump (the bar fills), let go, it jumps far higher than a hop
            case 1472 -> {
                if (mc.player.getVehicle() instanceof com.mattermod.entity.RedstoneGolem rg) rg.peakY = rg.getY();
            }
            case 1473 -> opts.keyJump.setDown(true);
            case 1485 -> opts.keyJump.setDown(false);
            case 1490 -> sideCamera(mc, true);
            case 1493 -> shot(mc, "37_golem_charged_jump");
            case 1494 -> sideCamera(mc, false);
            case 1506 -> {
                double rise = mc.player.getVehicle() instanceof com.mattermod.entity.RedstoneGolem rg ? rg.peakY - rg.getY() : -1;
                log((rise > 2.5 ? "PASS" : "FAIL") + " golem: a charged jump rose " + String.format("%.2f", rise) + " blocks (want > 2.5)");
            }
            case 1508 ->
                    server(mc, (s, p) -> {
                        p.stopRiding();
                        var g = com.mattermod.entity.GolemControl.golem(p);
                        if (g != null) g.recall();
                    });
            case 1562 ->
                    server(
                            mc,
                            (s, p) -> {
                                log((com.mattermod.entity.GolemControl.golem(p) == null ? "PASS" : "FAIL")
                                        + " golem: recall put the Golem away");
                                run(s, "difficulty peaceful");
                            });
            case 1572 -> finish(mc);
            default -> {}
        }
    }

    /** Turns the player to look at the middle of the nearest pig. */
    private static void aimAtPig(Minecraft mc) {
        net.minecraft.world.entity.Entity best = null;
        for (var e : mc.level.entitiesForRendering())
            if (e instanceof net.minecraft.world.entity.animal.Pig pig && pig.isAlive() && pig.deathTime == 0
                    && (best == null || e.distanceToSqr(mc.player) < best.distanceToSqr(mc.player))) best = e;
        if (best == null) {
            log("tether: no pig to aim at");
            return;
        }
        mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, best.getBoundingBox().getCenter());
        log("tether: aiming at the pig " + String.format("%.1f", best.distanceTo(mc.player)) + " blocks out, pitch "
                + String.format("%.1f", mc.player.getXRot()));
    }

    /** Views the scene from the "mmcam" armor stand, or back from the player. */
    private static void sideCamera(Minecraft mc, boolean on) {
        // hide the HUD and first-person hand while looking from the stand
        mc.options.hideGui = on;
        if (!on) {
            mc.setCameraEntity(mc.player);
            return;
        }
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof net.minecraft.world.entity.decoration.ArmorStand
                    && e.hasCustomName()
                    && "mmcam".equals(e.getCustomName().getString())) {
                mc.setCameraEntity(e);
                return;
            }
        }
        log("FAIL side camera: armor stand not found");
    }

    private static void checkMined(Minecraft mc) {
        if (mineBrokeAt > 0 || base == null) return;
        BlockPos target = new BlockPos(base.getX(), base.getY() + 1, base.getZ() - 4);
        if (mc.level.getBlockState(target).isAir()) mineBrokeAt = t - 70;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private interface ServerAction {
        void run(MinecraftServer server, ServerPlayer player);
    }

    private static void server(Minecraft mc, ServerAction action) {
        MinecraftServer s = mc.getSingleplayerServer();
        if (s == null) {
            log("FAIL no integrated server");
            return;
        }
        s.execute(
                () -> {
                    ServerPlayer p =
                            s.getPlayerList().getPlayers().isEmpty()
                                    ? null
                                    : s.getPlayerList().getPlayers().get(0);
                    if (p != null) action.run(s, p);
                });
    }

    /** The Manipulator advancement tab: all 24 load (10 of them secret), summoning earned the root, picking up a gem earns the gem
     *  hub and that gem's riddle, a gem unlocks its Module directly, and the Upgrades use the new recipes. */
    private static void checkAdvancements(MinecraftServer s, ServerPlayer p) {
        String[] ids = {"root", "calibrated", "augmented", "overclocked", "stratite", "sirenite", "atenite", "oculite",
            "zephyrite", "gehennite", "quetzalite", "tinkerite", "dye", "spectrum", "return_to_sender", "rain_check", "this_is_fine",
            "gone_with_the_wind", "night_light", "fish_out_of_water", "green_thumb", "heavyweight_bout", "let_it_go", "ping_pong"};
        int loaded = 0;
        for (String id : ids)
            if (s.getAdvancements().get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mattermod", "manipulator/" + id)) != null) loaded++;
        log((loaded == ids.length ? "PASS" : "FAIL") + " advancements: " + loaded + "/" + ids.length + " in the Manipulator tab");
        java.util.function.Predicate<String> done = id -> {
            var h = s.getAdvancements().get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mattermod", "manipulator/" + id));
            return h != null && p.getAdvancements().getOrStartProgress(h).isDone();
        };
        com.mattermod.manipulator.Manipulator.setSummoned(p, true);
        log((done.test("root") ? "PASS" : "FAIL") + " advancements: summoning earns the root");
        p.getInventory().add(new net.minecraft.world.item.ItemStack(com.mattermod.registry.ModItems.GEMS.get("sirenite").get()));
        p.containerMenu.broadcastChanges();
        p.inventoryMenu.broadcastChanges();
        log((!done.test("sirenite") ? "PASS" : "FAIL") + " advancements: only holding a Sirenite earns nothing yet");
        var mm = com.mattermod.manipulator.Manipulator.stack(p);
        var gem = new net.minecraft.world.item.ItemStack(com.mattermod.registry.ModItems.GEMS.get("quetzalite").get());
        p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, gem);
        boolean before = com.mattermod.trait.ModuleUnlocks.known(mm, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mattermod", "cultivation"));
        gem.getItem().use(p.serverLevel(), p, net.minecraft.world.InteractionHand.OFF_HAND);
        boolean after = com.mattermod.trait.ModuleUnlocks.known(com.mattermod.manipulator.Manipulator.stack(p),
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mattermod", "cultivation"));
        log((after ? "PASS" : "FAIL") + " gems: using a Quetzalite unlocks the Botanic Module (before " + before + ")");
        log((done.test("quetzalite") && !done.test("stratite") ? "PASS" : "FAIL")
                + " advancements: unlocking the Botanic Module earns Feathered Serpent only");
        p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, net.minecraft.world.item.ItemStack.EMPTY);
        boolean noModules = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mattermod", "module_hydraulics")).isEmpty();
        log((noModules ? "PASS" : "FAIL") + " gems: the Module items are gone");
        for (String[] r : new String[][] {{"upgrade_iron", "minecraft:iron_block", "minecraft:amethyst_shard"}, {"upgrade_diamond", "minecraft:gold_block", "minecraft:diamond"}}) {
            var rec = s.getRecipeManager().byKey(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("mattermod", r[0]));
            boolean ok = rec.isPresent() && rec.get().value().getIngredients().size() == 9
                    && rec.get().value().getIngredients().get(1).test(new net.minecraft.world.item.ItemStack(
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(r[1]))))
                    && rec.get().value().getIngredients().get(4).test(new net.minecraft.world.item.ItemStack(
                            net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(r[2]))))
                    && rec.get().value().getIngredients().get(3).isEmpty();
            log((ok ? "PASS" : "FAIL") + " recipes: " + r[0] + " is ingot/block/ingot with the gem in the waist");
        }
        p.getInventory().clearOrCountMatchingItems(st -> com.mattermod.registry.ModItems.GEMS.values().stream().anyMatch(g -> st.is(g.get())), -1, p.inventoryMenu.getCraftSlots());
    }

    /** Rolls each gem's structure chest 400 times: the gem must show up, and only that structure's gem. */
    private static void checkGemLoot(MinecraftServer s, ServerPlayer p) {
        String[][] want = {
            {"chests/shipwreck_treasure", "sirenite"}, {"chests/underwater_ruin_big", "sirenite"}, {"chests/ruined_portal", "gehennite"},
            {"chests/nether_bridge", "gehennite"}, {"chests/abandoned_mineshaft", "stratite"}, {"chests/trial_chambers/reward", "zephyrite"},
            {"chests/jungle_temple", "quetzalite"}, {"chests/pillager_outpost", "tinkerite"}, {"chests/woodland_mansion", "tinkerite"},
            {"chests/desert_pyramid", "atenite"}, {"chests/stronghold_library", "oculite"}, {"chests/end_city_treasure", "oculite"}
        };
        for (String[] w : want) {
            var key = net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.LOOT_TABLE,
                    net.minecraft.resources.ResourceLocation.withDefaultNamespace(w[0]));
            var table = s.reloadableRegistries().getLootTable(key);
            var gem = com.mattermod.registry.ModItems.GEMS.get(w[1]).get();
            int hits = 0, wrong = 0;
            for (int i = 0; i < 400; i++) {
                var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(p.serverLevel())
                        .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, p.position())
                        .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.CHEST);
                for (var st : table.getRandomItems(params)) {
                    if (st.is(gem)) hits++;
                    else if (com.mattermod.registry.ModItems.GEMS.values().stream().anyMatch(g -> st.is(g.get()))) wrong++;
                }
            }
            boolean always = w[0].equals("chests/ruined_portal");
            log((hits > 0 && wrong == 0 && (!always || hits == 400) ? "PASS" : "FAIL") + " gems: " + w[0] + " gave " + w[1] + " " + hits + "/400, other gems " + wrong);
        }
        // buried treasure: always exactly one gem, any of them
        var bt = s.reloadableRegistries().getLootTable(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.LOOT_TABLE, net.minecraft.resources.ResourceLocation.withDefaultNamespace("chests/buried_treasure")));
        int ok = 0;
        for (int i = 0; i < 100; i++) {
            var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(p.serverLevel())
                    .withParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.ORIGIN, p.position())
                    .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.CHEST);
            int n = 0;
            for (var st : bt.getRandomItems(params))
                if (com.mattermod.registry.ModItems.GEMS.values().stream().anyMatch(g -> st.is(g.get()))) n += st.getCount();
            if (n == 1) ok++;
        }
        log((ok == 100 ? "PASS" : "FAIL") + " gems: buried treasure held exactly one gem in " + ok + "/100");
    }

    /** Places a structure next to the arena and opens all its chests: the guaranteed gem must be in one. */
    private static void checkGemGuarantee(MinecraftServer s, ServerPlayer p, String structure, String gem, boolean hidden) {
        var level = p.serverLevel();
        // the desert's stand-in chests go well away from where the loot-rate rolls already claimed this area's guarantee
        var at = p.blockPosition().offset(0, 0, structure.contains("jungle") ? -40 : 140);
        if (structure.equals("desert_pyramid")) {
            // its treasure room sits 11 blocks down, under the superflat bedrock: build the room's
            // four chests with the pyramid's loot table instead
            for (var d : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                var c = at.relative(d, 2);
                level.setBlockAndUpdate(c, net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
                if (level.getBlockEntity(c) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest)
                    chest.setLootTable(net.minecraft.world.level.storage.loot.BuiltInLootTables.DESERT_PYRAMID, 7L + d.ordinal());
            }
        } else
            s.getCommands().performPrefixedCommand(s.createCommandSourceStack().withSuppressedOutput(),
                    "place structure minecraft:" + structure + " " + at.getX() + " " + at.getY() + " " + at.getZ());
        var item = com.mattermod.registry.ModItems.GEMS.get(gem).get();
        int chests = 0, withGem = 0, stickyWithGem = 0;
        StringBuilder where = new StringBuilder();
        for (var pos : net.minecraft.core.BlockPos.betweenClosed(at.offset(-24, -24, -24), at.offset(24, 24, 24))) {
            if (!(level.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity c) || c.getLootTable() == null) continue;
            chests++;
            c.unpackLootTable(p);
            boolean has = false;
            for (int i = 0; i < c.getContainerSize(); i++) if (c.getItem(i).is(item)) has = true;
            boolean sticky = false;
            for (var q : net.minecraft.core.BlockPos.betweenClosed(pos.offset(-4, -3, -4), pos.offset(4, 3, 4)))
                if (level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.STICKY_PISTON)) sticky = true;
            if (has) withGem++;
            if (has && sticky) stickyWithGem++;
            where.append(" ").append(pos.getX() - at.getX()).append(",").append(pos.getY() - at.getY()).append(",").append(pos.getZ() - at.getZ())
                    .append(has ? "+gem" : "").append(sticky ? "+sticky" : "");
        }
        boolean pass = chests > 0 && withGem >= 1 && (!hidden || stickyWithGem == 1);
        log((pass ? "PASS" : "FAIL") + " gems: placed " + structure + ", " + chests + " chests, " + withGem + " with " + gem + ":" + where);
    }

    private static net.minecraft.world.entity.monster.Husk husk(ServerPlayer p, String tag) {
        return p.serverLevel()
                .getEntitiesOfClass(
                        net.minecraft.world.entity.monster.Husk.class,
                        p.getBoundingBox().inflate(32),
                        h -> h.getTags().contains(tag))
                .stream()
                .findFirst()
                .orElse(null);
    }

    private static double startY;
    private static int bursts;

    private static int harvested;

    /** The closest the reeled pig came to the player. */
    private static double tetherClosest = 99;

    private static List<net.minecraft.world.entity.animal.Pig> pigs(ServerPlayer p) {
        return p.serverLevel()
                .getEntitiesOfClass(
                        net.minecraft.world.entity.animal.Pig.class,
                        p.getBoundingBox().inflate(24));
    }

    /** Light blocks around the Beacon's target in the left-click field (client view). */
    private static java.util.Set<BlockPos> lightsBefore = new java.util.HashSet<>();

    private static java.util.Set<BlockPos> lightSpots(Minecraft mc) {
        int bx = base.getX() + 60, y = base.getY(), bz = base.getZ();
        var out = new java.util.HashSet<BlockPos>();
        for (BlockPos q : BlockPos.betweenClosed(bx - 2, y - 1, bz + 4, bx + 2, y + 3, bz + 9))
            if (mc.level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.LIGHT)) out.add(q.immutable());
        return out;
    }

    private static int lights(Minecraft mc) {
        int bx = base.getX() + 60, y = base.getY(), bz = base.getZ(), n = 0;
        for (BlockPos q : BlockPos.betweenClosed(bx - 2, y - 1, bz + 4, bx + 2, y + 3, bz + 9))
            if (mc.level.getBlockState(q).is(net.minecraft.world.level.block.Blocks.LIGHT)) n++;
        return n;
    }

    /** Beam mode's crystal at rest keeps the colour of the last use: Kinetic or Plasmic. */
    private static void crystal(Minecraft mc, boolean orb, String when) {
        var st = Manipulator.stack(mc.player);
        int c = com.mattermod.item.ManipulatorColors.crystal(st, false);
        int want =
                orb
                        ? com.mattermod.item.OrbMode.ENERGY.color
                        : com.mattermod.item.ManipulatorColors.PLASMIC;
        log(
                (c == want ? "PASS" : "FAIL")
                        + " crystal "
                        + when
                        + ": "
                        + Integer.toHexString(c)
                        + " (want "
                        + Integer.toHexString(want)
                        + ")");
    }

    /** Switches the Manipulator's orb mode and fills the shared tank (server side). */
    private static void orbMode(ServerPlayer p, com.mattermod.item.OrbMode mode) {
        ItemStack mm = Manipulator.stack(p);
        // the modes come with their trait line: equip the lines this mode needs
        com.mattermod.item.ManipulatorStage.set(mm, com.mattermod.item.ManipulatorStage.NETHERITE);
        net.minecraft.resources.ResourceLocation own =
                switch (mode) {
                    case LIGHT -> com.mattermod.trait.Traits.PHOTONIC;
                    case TETHER -> com.mattermod.trait.Traits.MOBILITY;
                    case GUST -> com.mattermod.trait.Traits.PNEUMATIC;
                    case MASS -> com.mattermod.trait.Traits.EXCAVATION;
                    case LAVA, IGNITE -> com.mattermod.trait.Traits.PYROTECHNICS;
                    case GROWTH -> com.mattermod.trait.Traits.CULTIVATION;
                    case GOLEM -> com.mattermod.trait.Traits.MECHATRONIC;
                    default -> com.mattermod.trait.Traits.HYDRAULICS;
                };
        net.minecraft.resources.ResourceLocation second =
                own.equals(com.mattermod.trait.Traits.HYDRAULICS)
                        ? com.mattermod.trait.Traits.PNEUMATIC
                        : com.mattermod.trait.Traits.HYDRAULICS;
        net.minecraft.resources.ResourceLocation third =
                own.equals(com.mattermod.trait.Traits.CULTIVATION)
                                || second.equals(com.mattermod.trait.Traits.CULTIVATION)
                        ? com.mattermod.trait.Traits.PYROTECHNICS
                        : com.mattermod.trait.Traits.CULTIVATION;
        com.mattermod.trait.TraitBuild.setLine(mm, 0, own);
        com.mattermod.trait.TraitBuild.setLine(mm, 1, second);
        com.mattermod.trait.TraitBuild.setLine(mm, 2, third);
        com.mattermod.item.OrbMode.set(mm, mode);
        // fill the shared tank
        com.mattermod.item.OrbMode.setTank(mm, com.mattermod.item.OrbMode.capacity(mm));
        Manipulator.sync(p);
    }

    private static void run(MinecraftServer s, String command) {
        s.getCommands()
                .performPrefixedCommand(
                        s.createCommandSourceStack().withSuppressedOutput(), command);
    }

    private static void shot(Minecraft mc, String name) {
        mc.gui.getChat().clearMessages(false);
        mc.getToasts().clear();
        Consumer<net.minecraft.network.chat.Component> sink = c -> {};
        Screenshot.grab(mc.gameDirectory, "mm_" + name + ".png", mc.getMainRenderTarget(), sink);
        log("shot " + name);
    }

    private static void log(String line) {
        report.add(line);
        com.mattermod.MatterMod.LOGGER.info("[autotest] {}", line);
    }

    private static void finish(Minecraft mc) {
        done = true;
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);
        mc.options.keyShift.setDown(false);
        try {
            Files.write(
                    Path.of(mc.gameDirectory.getPath(), "mattermod-autotest.txt"),
                    report,
                    StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
        mc.stop();
    }
}
