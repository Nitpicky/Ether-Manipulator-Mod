package com.ethermod.client;

import com.ethermod.item.*;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.*;

import net.minecraft.client.Minecraft;

import java.nio.file.*;
import java.util.*;

public final class UiProbe {
    static net.minecraft.core.BlockPos noCrackFire;
    static int tick;
    static List<String> report = new ArrayList<>();

    private static final class WalkProbe extends net.minecraft.world.entity.player.Player {
        net.minecraft.world.phys.Vec3 step = net.minecraft.world.phys.Vec3.ZERO;

        WalkProbe(net.minecraft.world.level.Level level) {
            super(
                    level,
                    net.minecraft.core.BlockPos.ZERO,
                    0,
                    new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "WalkProbe"));
        }

        public boolean isSpectator() {
            return false;
        }

        public boolean isCreative() {
            return false;
        }

        @Override
        public void aiStep() {
            setPos(position().add(step));
            calculateEntityAnimation(false);
        }
    }

    /**
     * Sets a pick and fails loudly if it is not a real choice for that tier. An invalid id is
     * silently dropped by TraitChoices.resolve (and blanks the tiers above it), so a setup with a
     * typo or retired id would otherwise test fewer traits than it claims.
     */
    static void setPickChecked(
            net.minecraft.world.item.ItemStack st, int slot, int tier, String id) {
        String module = id.substring(0, Math.max(0, id.indexOf('.')));
        if (!id.isEmpty() && !TraitChoices.accepts(module, tier, id)) // "" clears the pick
            check("Setup pick is a real choice: " + id + " (tier " + tier + ")", false);
        TraitBuild.setPick(st, slot, tier, id);
    }

    static void check(String name, boolean ok) {
        String line = (ok ? "PASS " : "FAIL ") + name;
        report.add(line);
        System.out.println(line);
    }

    private static void poseTick(net.minecraft.world.entity.player.Player p) {
        try {
            var method =
                    net.minecraft.world.entity.player.Player.class.getDeclaredMethod(
                            "updatePlayerPose");
            method.setAccessible(true);
            method.invoke(p);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean fitsPose(
            net.minecraft.world.entity.player.Player p, net.minecraft.world.entity.Pose pose) {
        try {
            var method =
                    net.minecraft.world.entity.player.Player.class.getDeclaredMethod(
                            "canPlayerFitWithinBlocksAndEntitiesWhen",
                            net.minecraft.world.entity.Pose.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(p, pose);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void step(Minecraft mc) {
        if (Boolean.getBoolean("ethermod.kGifs")) { KGifTour.step(mc); return; }
        if (Boolean.getBoolean("ethermod.hoverScreenshots")) { HoverScreenshotTour.step(mc); return; }
        if (Boolean.getBoolean("ethermod.pairScreenshots")) { PairScreenshotTour.step(mc); return; }
        tick++;
        if (Boolean.getBoolean("ethermod.uiInteractive")) {
            if (tick == 15)
                mc.getSingleplayerServer()
                        .execute(
                                () -> {
                                    var p =
                                            mc.getSingleplayerServer()
                                                    .getPlayerList()
                                                    .getPlayer(mc.player.getUUID());
                                    var st = Manipulator.stack(p);
                                    if (Boolean.getBoolean("ethermod.blazelingTest")) {
                                        ManipulatorStage.set(st, ManipulatorStage.NETHERITE);
                                        for (var line : TraitLines.all(p.registryAccess()))
                                            ModuleUnlocks.unlock(st, line.getKey());
                                        for (int slot = 0; slot < 3; slot++)
                                            TraitBuild.setLine(st, slot, null);
                                        TraitBuild.setLine(st, 0, Traits.PYROTECHNIC);
                                        setPickChecked(st, 0, 0, "pyrotechnic.crucible");
                                        setPickChecked(st, 0, 1, "pyrotechnic.flashover");
                                        setPickChecked(st, 0, 2, "pyrotechnic.soulwake");
                                        OrbMode.set(st, OrbMode.IGNITE);
                                        OrbMode.setTank(st, 40);
                                        Manipulator.setSummoned(p, true);
                                        p.getServer().getPlayerList().op(p.getGameProfile());
                                        p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
                                        p.getInventory()
                                                .add(
                                                        new net.minecraft.world.item.ItemStack(
                                                                net.minecraft.world.item.Items
                                                                        .ZOMBIE_SPAWN_EGG,
                                                                64));
                                        p.getInventory()
                                                .add(
                                                        new net.minecraft.world.item.ItemStack(
                                                                net.minecraft.world.item.Items
                                                                        .HUSK_SPAWN_EGG,
                                                                64));
                                        p.getInventory()
                                                .add(
                                                        new net.minecraft.world.item.ItemStack(
                                                                net.minecraft.world.item.Items
                                                                        .NETHERITE_SWORD));
                                        p.serverLevel().setDayTime(18000);
                                        p.serverLevel()
                                                .getGameRules()
                                                .getRule(
                                                        net.minecraft.world.level.GameRules
                                                                .RULE_DAYLIGHT)
                                                .set(false, p.getServer());
                                        p.serverLevel()
                                                .getGameRules()
                                                .getRule(
                                                        net.minecraft.world.level.GameRules
                                                                .RULE_DOMOBSPAWNING)
                                                .set(false, p.getServer());
                                        var pet =
                                                com.ethermod.registry.ModEntities.SOUL_BLAZELING
                                                        .get()
                                                        .create(p.serverLevel());
                                        pet.configure(p);
                                        pet.setPos(p.position().add(2, 1, 0));
                                        p.serverLevel().addFreshEntity(pet);
                                        for (int n = 0; n < 4; n++) {
                                            var mob =
                                                    net.minecraft.world.entity.EntityType.HUSK
                                                            .create(p.serverLevel());
                                            mob.setPos(p.position().add(n * 2 - 3, 0, 9));
                                            p.serverLevel().addFreshEntity(mob);
                                        }
                                        Manipulator.sync(p);
                                        p.sendSystemMessage(
                                                net.minecraft.network.chat.Component.literal(
                                                        "Blazeling test: Soulwake + Ignite ready. R"
                                                            + " summons the MM, right-click fires."
                                                            + " Your pet's burns can summon more."
                                                            + " Spawn eggs are in your"
                                                            + " inventory."));
                                        return;
                                    }
                                    if (Boolean.getBoolean("ethermod.uiKeepState")
                                            && ManipulatorStage.get(st)
                                                    == ManipulatorStage.NETHERITE) {
                                        Manipulator.setSummoned(p, true);
                                        Manipulator.sync(p);
                                        return;
                                    }
                                    ManipulatorStage.set(st, ManipulatorStage.NETHERITE);
                                    for (var line : TraitLines.all(p.registryAccess()))
                                        ModuleUnlocks.unlock(st, line.getKey());
                                    for (int i = 0; i < 3; i++) TraitBuild.setLine(st, i, null);
                                    TraitBuild.setLine(st, 0, Traits.GRAVITIC);
                                    setPickChecked(st, 0, 0, "gravitic.gravity_shift");
                                    setPickChecked(st, 0, 1, "gravitic.skyhook");
                                    setPickChecked(st, 0, 2, "gravitic.airwalk");
                                    TraitBuild.setLine(st, 1, Traits.PNEUMATIC);
                                    setPickChecked(st, 1, 0, "pneumatic.gale_force");
                                    setPickChecked(st, 1, 1, "pneumatic.bunny_hop");
                                    setPickChecked(st, 1, 2, "pneumatic.cyclone");
                                    TraitBuild.setLine(st, 2, Traits.PHOTONIC);
                                    setPickChecked(st, 2, 0, "photonic.radiance");
                                    setPickChecked(st, 2, 1, "photonic.daybreak");
                                    setPickChecked(st, 2, 2, "photonic.constellation");
                                    OrbMode.set(st, OrbMode.TETHER);
                                    p.getServer().getPlayerList().op(p.getGameProfile());
                                    p.setHealth(p.getMaxHealth());
                                    p.getInventory()
                                            .add(
                                                    new net.minecraft.world.item.ItemStack(
                                                            net.minecraft.world.item.Items
                                                                    .FIREWORK_ROCKET,
                                                            64));
                                    for (var module :
                                            com.ethermod.registry.ModItems.GEMS.values())
                                        p.getInventory()
                                                .add(
                                                        new net.minecraft.world.item.ItemStack(
                                                                module.get()));
                                    net.minecraft.world.item.component.CustomData.update(
                                            net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                                            st,
                                            t -> {
                                                t.putInt("mm_tank", 40);
                                            });
                                    var sl = p.serverLevel();
                                    var origin = p.blockPosition();
                                    for (int x = -7; x <= 7; x++)
                                        for (int z = -7; z <= 7; z++)
                                            sl.setBlockAndUpdate(
                                                    origin.offset(x, 12, z),
                                                    net.minecraft.world.level.block.Blocks.STONE
                                                            .defaultBlockState());
                                    for (int y = 0; y < 9; y++)
                                        for (int z = -8; z <= 8; z++)
                                            sl.setBlockAndUpdate(
                                                    origin.offset(12, y, z),
                                                    net.minecraft.world.level.block.Blocks.STONE
                                                            .defaultBlockState());
                                    for (int x = -4; x <= 4; x++)
                                        for (int z = -4; z <= 4; z++)
                                            sl.setBlockAndUpdate(
                                                    origin.offset(x, 8, z + 18),
                                                    net.minecraft.world.level.block.Blocks.STONE
                                                            .defaultBlockState());
                                    for (int x = -3; x <= 3; x++)
                                        for (int z = -3; z <= 3; z++) {
                                            sl.setBlockAndUpdate(
                                                    origin.offset(x, -1, z - 16),
                                                    net.minecraft.world.level.block.Blocks.STONE
                                                            .defaultBlockState());
                                            sl.setBlockAndUpdate(
                                                    origin.offset(x, 0, z - 16),
                                                    net.minecraft.world.level.block.Blocks.WATER
                                                            .defaultBlockState());
                                        }
                                    p.getInventory()
                                            .add(
                                                    new net.minecraft.world.item.ItemStack(
                                                            net.minecraft.world.item.Items.WHEAT,
                                                            64));
                                    p.getInventory()
                                            .add(
                                                    new net.minecraft.world.item.ItemStack(
                                                            net.minecraft.world.item.Items
                                                                    .WHEAT_SEEDS,
                                                            64));
                                    p.getInventory()
                                            .add(
                                                    new net.minecraft.world.item.ItemStack(
                                                            net.minecraft.world.item.Items
                                                                    .NETHERITE_SWORD));
                                    var spawn = origin.offset(0, 13, 0);
                                    p.teleportTo(
                                            sl,
                                            spawn.getX() + .5,
                                            spawn.getY(),
                                            spawn.getZ() + .5,
                                            java.util.Set.of(),
                                            0,
                                            0);
                                    Manipulator.setSummoned(p, true);
                                    Manipulator.sync(p);
                                });
            if (tick == 40 && !Boolean.getBoolean("ethermod.blazelingTest"))
                mc.setScreen(new TraitScreen());
            if (tick == 60) {
                System.out.println("PLAYTEST READY - control handed to player");
                if (!Boolean.getBoolean("ethermod.noScreenshots"))
                    net.minecraft.client.Screenshot.grab(
                            mc.gameDirectory,
                            "playtest-ready.png",
                            mc.getMainRenderTarget(),
                            c -> {});
            }
            return;
        }

        if (tick == 15)
            mc.getSingleplayerServer()
                    .execute(
                            () -> {
                                var p =
                                        mc.getSingleplayerServer()
                                                .getPlayerList()
                                                .getPlayer(mc.player.getUUID());
                                var st = Manipulator.stack(p);
                                net.minecraft.world.item.component.CustomData.update(
                                        net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                                        st,
                                        t -> {
                                            t.remove("mm_inhibit");
                                            t.remove("mm_signal_strength");
                                        });
                                ManipulatorStage.set(st, ManipulatorStage.NETHERITE);
                                for (int slot = 0; slot < 3; slot++)
                                    TraitBuild.setLine(st, slot, null);
                                check(
                                        "Client SideHooks installed (common code reaches client code)",
                                        com.ethermod.SideHooks.get().getClass()
                                                != com.ethermod.SideHooks.class);
                                check(
                                        "Nine Module definitions (Mechatronic shelved)",
                                        TraitLines.all(p.registryAccess()).size() == 9);
                                check(
                                        "Default Plasmic/Kinetic knowledge",
                                        ModuleUnlocks.known(st, Traits.PLASMIC)
                                                && ModuleUnlocks.known(st, Traits.KINETIC));
                                for (var e : TraitLines.all(p.registryAccess())) {
                                    check(
                                            e.getValue().name() + " 6 choices + 2 Commons",
                                            e.getValue().adept().size() == 2
                                                    && e.getValue().master().size() == 2
                                                    && e.getValue().grandmaster().size() == 2
                                                    && e.getValue().commons().size() == 2);
                                    ModuleUnlocks.unlock(st, e.getKey());
                                }
                                TraitRules.equip(p, 0, Traits.HYDRAULIC);
                                TraitRules.pick(p, 0, 0, 1); // Reservoir
                                check("Reservoir grows the shared tank", OrbMode.capacity(st) == 8);
                                check(
                                        "Common follows prior choice",
                                        Traits.hasCommon(st, Traits.HYDRAULIC, 0));
                                setPickChecked(st, 0, 0, "");
                                check(
                                        "Common inactive without choice",
                                        !Traits.hasCommon(st, Traits.HYDRAULIC, 0));
                                setPickChecked(st, 0, 0, "hydraulic.reservoir");
                                OrbMode.setTank(st, 3);
                                OrbMode.set(st, OrbMode.FROST);
                                check("Mode switch retains tank", OrbMode.tank(st) == 3);
                                TraitBuild.setLine(st, 0, null);
                                TraitBuild.setLine(st, 0, Traits.HYDRAULIC);
                                check("Module switch retains tank", OrbMode.tank(st) == 3);
                                setPickChecked(st, 0, 0, "hydraulic.reservoir");
                                check(
                                        "Reload waits 15s at Prototype",
                                        com.ethermod.trait.TankReload.reloadDelay(new net.minecraft.world.item.ItemStack(com.ethermod.registry.ModItems.ETHER_MANIPULATOR.get())) == 300);
                                TraitRules.equip(p, 1, Traits.PYROTECHNIC);
                                TraitRules.pick(p, 1, 0, 1);
                                check("Crucible grows the shared tank", OrbMode.capacity(st) == 10);
                                TraitRules.equip(p, 2, Traits.BOTANIC);
                                TraitRules.pick(p, 2, 0, 1);
                                check(
                                        "Seedbank grows the shared tank",
                                        OrbMode.capacity(st) == 12);
                                check(
                                        "Frost spends water",
                                        OrbMode.FROST.shotCost(st, 2) == 1
                                                && OrbMode.FROST.usesTank());
                                check(
                                        "Ignite/Lava costs",
                                        OrbMode.IGNITE.shotCost(st, 0) == 1
                                                && OrbMode.LAVA.shotCost(st, 0) == 4
                                                && OrbMode.WATER.shotCost(st, 0) == 4
                                                && OrbMode.GROWTH.shotCost(st, 2) == 4
                                                && OrbMode.GRENADE_COST == 2);
                                check("Vacuum removed", !OrbMode.VACUUM.isUnlocked(st));
                                check(
                                        "No Tier scaling",
                                        Traits.rankOfTier(st, 0) == 1
                                                && Traits.rankOfTier(st, 1) == 1);
                                Manipulator.setSummoned(p, true);
                                Manipulator.sync(p);
                            });
        if (tick == 45)
            mc.getSingleplayerServer()
                    .execute(
                            () -> {
                                var p =
                                        mc.getSingleplayerServer()
                                                .getPlayerList()
                                                .getPlayer(mc.player.getUUID());
                                var sl = p.serverLevel();
                                var st = Manipulator.stack(p);
                                TraitBuild.setLine(st, 0, Traits.GRAVITIC);
                                setPickChecked(st, 0, 0, "gravitic.gravity_shift");
                                MovementPhysics.setGravity(p, net.minecraft.core.Direction.EAST);
                                check(
                                        "Gravity changes player bounds",
                                        p.getBoundingBox().getXsize()
                                                > p.getBoundingBox().getYsize());
                                check(
                                        "Gravity rotates up toward surface normal",
                                        MovementPhysics.rotate(
                                                                new net.minecraft.world.phys.Vec3(
                                                                        0, 1, 0),
                                                                net.minecraft.core.Direction.EAST)
                                                        .x
                                                < -.9);
                                MovementPhysics.setGravity(p, net.minecraft.core.Direction.DOWN);
                                check("Gravity reset", !p.isNoGravity());
                                TraitBuild.setLine(st, 0, null);
                                var at = p.blockPosition().offset(4, 1, 0);
                                sl.setBlockAndUpdate(
                                        at,
                                        net.minecraft.world.level.block.Blocks.STONE
                                                .defaultBlockState());
                                p.lookAt(
                                        net.minecraft.commands.arguments.EntityAnchorArgument.Anchor
                                                .EYES,
                                        net.minecraft.world.phys.Vec3.atCenterOf(at));
                                var zombie =
                                        net.minecraft.world.entity.EntityType.ZOMBIE.create(sl);
                                zombie.setPos(p.position().add(0, 0, 3));
                                sl.addFreshEntity(zombie);
                                Stasis.stasis(zombie, 20);
                                Stasis.impulse(
                                        zombie, new net.minecraft.world.phys.Vec3(1, 0, 0));
                                Stasis.impulse(
                                        zombie, new net.minecraft.world.phys.Vec3(0, 0, 2));
                                check(
                                        "Stasis accumulates force",
                                        zombie.getPersistentData().getDouble("mm_stasis_force")
                                                == Stasis.STASIS_MAX);
                                check(
                                        "Stasis last impulse sets direction",
                                        zombie.getPersistentData().getDouble("mm_stasis_z") == 1);
                                var decoy =
                                        com.ethermod.registry.ModEntities.LIGHT_DECOY
                                                .get()
                                                .create(sl);
                                decoy.configure(p, 40);
                                decoy.setPos(p.position().add(2, 0, 2));
                                sl.addFreshEntity(decoy);
                                check(
                                        "Mirage entity creates with owner skin identity",
                                        decoy.owner().equals(p.getUUID()));
                                check(
                                        "Mirage cannot be hurt",
                                        !decoy.hurt(sl.damageSources().playerAttack(p), 100)
                                                && decoy.isAlive());
                                var pocket = p.blockPosition().offset(6, 0, 0);
                                sl.setBlockAndUpdate(
                                        pocket,
                                        net.minecraft.world.level.block.Blocks.WATER
                                                .defaultBlockState());
                                sl.setBlockAndUpdate(
                                        pocket.east(),
                                        net.minecraft.world.level.block.Blocks.WATER
                                                .defaultBlockState());
                                TempBlocks.tempBlock(
                                        sl,
                                        pocket,
                                        net.minecraft.world.level.block.Blocks.AIR
                                                .defaultBlockState(),
                                        400);
                                Scheduler.schedule(
                                        sl,
                                        30,
                                        () ->
                                                check(
                                                        "Diving Bell blocks water entry",
                                                        sl.getBlockState(pocket).isAir()
                                                                && TempBlocks.isAirPocket(
                                                                        sl, pocket)));
                                var currentCell = p.blockPosition().offset(-9, 3, 0);
                                sl.setBlockAndUpdate(
                                        currentCell.below(),
                                        net.minecraft.world.level.block.Blocks.STONE
                                                .defaultBlockState());
                                sl.setBlockAndUpdate(
                                        currentCell,
                                        net.minecraft.world.level.block.Blocks.WATER
                                                .defaultBlockState());
                                var originalWater = sl.getBlockState(currentCell);
                                var originalFlow =
                                        sl.getFluidState(currentCell).getFlow(sl, currentCell);
                                RapidsCurrents.redirect(
                                        sl,
                                        net.minecraft.world.phys.Vec3.atCenterOf(currentCell),
                                        new net.minecraft.world.phys.Vec3(0, 0, 1),
                                        1);
                                check(
                                        "Rapids overrides actual water flow",
                                        sl.getFluidState(currentCell).getFlow(sl, currentCell).z
                                                > 3.9);
                                check(
                                        "Rapids preserves water blocks",
                                        sl.getBlockState(currentCell).equals(originalWater));
                                Scheduler.schedule(
                                        sl,
                                        101,
                                        () ->
                                                check(
                                                        "Rapids restores natural current",
                                                        RapidsCurrents.flow(sl, currentCell) == null
                                                                && sl.getFluidState(currentCell)
                                                                                .getFlow(
                                                                                        sl,
                                                                                        currentCell)
                                                                                .distanceTo(
                                                                                        originalFlow)
                                                                        < .0001));
                                // Probe support without relying on the player's current ground
                                // flag.
                                var saved = p.position();
                                var support = p.blockPosition().offset(-5, 3, 0);
                                sl.setBlockAndUpdate(
                                        support,
                                        net.minecraft.world.level.block.Blocks.STONE
                                                .defaultBlockState());
                                p.setPos(
                                        support.getX() + .5,
                                        support.getY() + 1,
                                        support.getZ() + .5);
                                p.setOnGround(false);
                                check(
                                        "Airwalk finds real support with stale ground flag",
                                        MovementPhysics.hasRealSupport(p));
                                p.setPos(p.position().add(2, 0, 0));
                                check(
                                        "Airwalk does not treat empty air as real support",
                                        !MovementPhysics.hasRealSupport(p));
                                p.setPos(saved);
                                // A sustained source effect, not an entity-hit-only impulse.
                                TraitBuild.setLine(st, 0, Traits.HYDRAULIC);
                                setPickChecked(st, 0, 0, "hydraulic.reservoir");
                                setPickChecked(st, 0, 1, "hydraulic.rapids");
                                var water = p.blockPosition().offset(9, 4, 0);
                                sl.setBlockAndUpdate(
                                        water.below(),
                                        net.minecraft.world.level.block.Blocks.STONE
                                                .defaultBlockState());
                                sl.setBlockAndUpdate(
                                        water,
                                        net.minecraft.world.level.block.Blocks.WATER
                                                .defaultBlockState());
                                var lifted = net.minecraft.world.entity.EntityType.PIG.create(sl);
                                lifted.setNoAi(true);
                                lifted.setNoGravity(true);
                                lifted.setPos(net.minecraft.world.phys.Vec3.atCenterOf(water));
                                sl.addFreshEntity(lifted);
                                var waterShot =
                                        new com.ethermod.beam.BeamBallEntity(
                                                sl,
                                                p,
                                                2,
                                                ManipulatorStage.NETHERITE,
                                                OrbMode.WATER);
                                waterShot.configure(st);
                                waterShot.setPos(net.minecraft.world.phys.Vec3.atCenterOf(water));
                                com.ethermod.beam.ProjectileEffects.waterImpact(
                                        waterShot, st.copy(), waterShot.position());
                                Scheduler.schedule(
                                        sl,
                                        4,
                                        () ->
                                                lifted.setDeltaMovement(
                                                        net.minecraft.world.phys.Vec3.ZERO));
                                check(
                                        "Old builds keep moved choices",
                                        TraitChoices.resolve(
                                                        "hydraulic",
                                                        java.util.List.of(
                                                                "hydraulic.reservoir",
                                                                "hydraulic.helmsman",
                                                                "hydraulic.glacier"),
                                                        1)
                                                .equals("hydraulic.glacier"));
                                {
                                    // Stasis keeps the creature's own flags on it, so a save
                                    // mid-stasis can't leave it floating forever
                                    var stasisPig = net.minecraft.world.entity.EntityType.PIG.create(sl);
                                    stasisPig.setPos(p.position().add(0, 0, 6));
                                    sl.addFreshEntity(stasisPig);
                                    Stasis.stasis(stasisPig, 40);
                                    boolean stasisSaved =
                                            stasisPig.getPersistentData().contains("mm_stasis_restore");
                                    Stasis.thaw(stasisPig);
                                    check(
                                            "Stasis saves and restores the creature's own flags",
                                            stasisSaved
                                                    && !stasisPig.getPersistentData()
                                                            .contains("mm_stasis_restore")
                                                    && !stasisPig.isNoGravity()
                                                    && !stasisPig.isNoAi());
                                    stasisPig.discard();
                                    var otherPlayer =
                                            net.neoforged.neoforge.common.util.FakePlayerFactory
                                                    .getMinecraft(sl);
                                    check(
                                            "Other players are no enemies while hurt_players is off",
                                            com.ethermod.config.ServerConfig.hurtPlayers()
                                                    || !Allegiance.enemy(p, otherPlayer));
                                }
                                var stone = p.blockPosition().offset(0, 1, 4);
                                sl.setBlockAndUpdate(
                                        stone,
                                        net.minecraft.world.level.block.Blocks.STONE
                                                .defaultBlockState());
                                p.lookAt(
                                        net.minecraft.commands.arguments.EntityAnchorArgument.Anchor
                                                .EYES,
                                        net.minecraft.world.phys.Vec3.atCenterOf(stone));
                                TraitBuild.setLine(st, 0, Traits.TECTONIC);
                                OrbMode.set(st, OrbMode.MASS);
                                check(
                                        "Mass Driver picks block",
                                        com.ethermod.beam.MassDriver.press(p, st));
                                check(
                                        "Mass Driver removes original once",
                                        sl.getBlockState(stone).isAir());
                                com.ethermod.beam.MassDriver.release(p, false);
                                check(
                                        "Mass Driver focus release preserves entity",
                                        sl.getEntitiesOfClass(
                                                                com.ethermod.beam.BeamBallEntity
                                                                        .class,
                                                                p.getBoundingBox().inflate(12),
                                                                b ->
                                                                        b.getMode() == OrbMode.MASS
                                                                                && !b.getCarried()
                                                                                        .isAir())
                                                        .size()
                                                == 1);
                                TraitBuild.setLine(st, 0, Traits.BOTANIC);
                                setPickChecked(st, 0, 0, "botanic.seedbank");
                                setPickChecked(st, 0, 1, "botanic.groundskeeper");
                                setPickChecked(st, 0, 2, "botanic.bountiful");
                                var crop = p.blockPosition().offset(-2, 0, 2);
                                sl.setBlockAndUpdate(
                                        crop.below(),
                                        net.minecraft.world.level.block.Blocks.FARMLAND
                                                .defaultBlockState());
                                sl.setBlockAndUpdate(
                                        crop,
                                        net.minecraft.world.level.block.Blocks.WHEAT
                                                .defaultBlockState()
                                                .setValue(
                                                        net.minecraft.world.level.block.CropBlock
                                                                .AGE,
                                                        7));
                                p.gameMode.destroyBlock(crop);
                                Scheduler.schedule(
                                        sl,
                                        3,
                                        () ->
                                                check(
                                                        "Replant puts a fresh crop back",
                                                        sl.getBlockState(crop)
                                                                        .is(
                                                                                net.minecraft.world
                                                                                        .level.block
                                                                                        .Blocks
                                                                                        .WHEAT)
                                                                && sl.getBlockState(crop)
                                                                                .getValue(
                                                                                        net
                                                                                                .minecraft
                                                                                                .world
                                                                                                .level
                                                                                                .block
                                                                                                .CropBlock
                                                                                                .AGE)
                                                                        == 0));
                                TraitBuild.setLine(st, 0, Traits.PHOTONIC);
                                setPickChecked(st, 0, 0, "photonic.radiance");
                                setPickChecked(st, 0, 1, "photonic.daybreak");
                                setPickChecked(st, 0, 2, "photonic.supernova");
                                var light =
                                        new com.ethermod.beam.BeamBallEntity(
                                                sl,
                                                p,
                                                2,
                                                ManipulatorStage.NETHERITE,
                                                OrbMode.LIGHT);
                                light.configure(st);
                                light.setPos(p.position());
                                p.getPersistentData().remove("mm_radiance_next");
                                p.setHealth(10);
                                com.ethermod.beam.PhotonicEffects.flight(light, st);
                                float healed = p.getHealth();
                                check("Radiance heals user", healed > 10);
                                com.ethermod.beam.PhotonicEffects.flight(light, st);
                                check("Radiance overlap does not stack", p.getHealth() == healed);
                                TraitBuild.setLine(st, 0, Traits.PYROTECHNIC);
                                setPickChecked(st, 0, 0, "pyrotechnic.crucible");
                                setPickChecked(st, 0, 1, "pyrotechnic.flashover");
                                setPickChecked(st, 0, 2, "pyrotechnic.soulwake");
                                var victim =
                                        net.minecraft.world.entity.EntityType.ZOMBIE.create(sl);
                                victim.setPos(p.position().add(0, 0, -3));
                                sl.addFreshEntity(victim);
                                Burning.burn(victim, p, 5, 0);
                                victim.hurt(sl.damageSources().playerAttack(p), 1000);
                                var pets =
                                        sl.getEntitiesOfClass(
                                                com.ethermod.entity.SoulBlazeling.class,
                                                p.getBoundingBox().inflate(16),
                                                e -> e.getPersistentData().hasUUID("mm_summoner"));
                                check("Soulfire kill raises allied Blazeling", pets.size() == 1);
                                var pet = pets.get(0);
                                check(
                                        "Blazeling keeps owner and lifetime",
                                        pet.summoner() == p
                                                && pet.getPersistentData()
                                                                .getLong("mm_summon_expires")
                                                        == sl.getGameTime()
                                                                + 600);
                                check(
                                        "Blazeling cannot target owner or summons",
                                        !pet.validTarget(p, p)
                                                && !pet.validTarget(p, pet)
                                                && Allegiance.ally(p, pet));
                                var boltTarget =
                                        net.minecraft.world.entity.EntityType.ZOMBIE.create(sl);
                                boltTarget.setNoAi(true);
                                boltTarget.setPos(p.position().add(5, 0, 0));
                                sl.addFreshEntity(boltTarget);
                                pet.fireAt(boltTarget);
                                var bolts =
                                        sl.getEntitiesOfClass(
                                                com.ethermod.entity.SoulBolt.class,
                                                pet.getBoundingBox().inflate(3));
                                check(
                                        "Blazeling emits one straight bolt",
                                        bolts.size() == 1
                                                && bolts.get(0).getOwner() == pet
                                                && bolts.get(0)
                                                        instanceof
                                                        net.minecraft.world.entity.projectile
                                                                .SmallFireball
                                                && bolts.get(0).getDeltaMovement().length() > .09);
                                try {
                                    var method =
                                            com.ethermod.entity.SoulBolt.class.getDeclaredMethod(
                                                    "onHitEntity",
                                                    net.minecraft.world.phys.EntityHitResult.class);
                                    method.setAccessible(true);
                                    method.invoke(
                                            bolts.get(0),
                                            new net.minecraft.world.phys.EntityHitResult(
                                                    boltTarget));
                                    check(
                                            "Blazeling bolt deals modest damage and player-owned"
                                                + " soulfire",
                                            boltTarget.getHealth() < 20
                                                    && boltTarget.getHealth() > 14
                                                    && boltTarget.isOnFire()
                                                    && boltTarget
                                                            .getPersistentData()
                                                            .getBoolean("mm_soul")
                                                    && p.getUUID()
                                                            .equals(
                                                                    boltTarget
                                                                            .getPersistentData()
                                                                            .getUUID(
                                                                                    "mm_burn_owner")));
                                    boltTarget.invulnerableTime = 0;
                                    boltTarget.setHealth(1);
                                    var lethal =
                                            com.ethermod.registry.ModEntities.SOUL_BOLT
                                                    .get()
                                                    .create(sl);
                                    lethal.setOwner(pet);
                                    lethal.setPos(boltTarget.position());
                                    sl.addFreshEntity(lethal);
                                    method.invoke(
                                            lethal,
                                            new net.minecraft.world.phys.EntityHitResult(
                                                    boltTarget));
                                    check(
                                            "Blazeling-owned soulfire can trigger Soulwake again",
                                            sl.getEntitiesOfClass(
                                                                    com.ethermod.entity
                                                                            .SoulBlazeling.class,
                                                                    p.getBoundingBox().inflate(16))
                                                            .size()
                                                    == 2);
                                    var block = p.blockPosition().offset(0, 2, 6);
                                    var prior = sl.getBlockState(block);
                                    var impact =
                                            com.ethermod.registry.ModEntities.SOUL_BOLT
                                                    .get()
                                                    .create(sl);
                                    impact.setOwner(pet);
                                    impact.setPos(net.minecraft.world.phys.Vec3.atCenterOf(block));
                                    sl.addFreshEntity(impact);
                                    var hit =
                                            com.ethermod.entity.SoulBolt.class.getDeclaredMethod(
                                                    "onHitBlock",
                                                    net.minecraft.world.phys.BlockHitResult.class);
                                    hit.setAccessible(true);
                                    hit.invoke(
                                            impact,
                                            new net.minecraft.world.phys.BlockHitResult(
                                                    net.minecraft.world.phys.Vec3.atCenterOf(block),
                                                    net.minecraft.core.Direction.UP,
                                                    block,
                                                    false));
                                    check(
                                            "Blazeling bolt does not place fire or alter terrain",
                                            sl.getBlockState(block).equals(prior)
                                                    && impact.isRemoved());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                                for (int n = 0; n < 9; n++) {
                                    var killed =
                                            net.minecraft.world.entity.EntityType.ZOMBIE.create(sl);
                                    killed.setPos(p.position().add(0, 0, 5));
                                    sl.addFreshEntity(killed);
                                    Burning.soulBurn(killed, p, 3);
                                    killed.hurt(sl.damageSources().playerAttack(p), 1000);
                                }
                                check(
                                        "Soulwake chain shares eight summon cap",
                                        sl.getEntitiesOfClass(
                                                                com.ethermod.entity.SoulBlazeling
                                                                        .class,
                                                                p.getBoundingBox().inflate(64))
                                                        .size()
                                                == 8);
                                var ground = p.blockPosition().offset(12, 4, 12);
                                sl.setBlockAndUpdate(
                                        ground.below(),
                                        net.minecraft.world.level.block.Blocks.STONE
                                                .defaultBlockState());
                                check(
                                        "Soulwake terrain fire is actual soul fire",
                                        OwnedFire.placeFire(sl, ground, p)
                                                && sl.getBlockState(ground)
                                                        .is(
                                                                net.minecraft.world.level.block
                                                                        .Blocks.SOUL_FIRE)
                                                && sl.getBlockState(ground).canSurvive(sl, ground));
                                var touching =
                                        net.minecraft.world.entity.EntityType.ZOMBIE.create(sl);
                                touching.tickCount = 5;
                                touching.setPos(
                                        net.minecraft.world.phys.Vec3.atBottomCenterOf(ground));
                                sl.addFreshEntity(touching);
                                OwnedFire.burnFromOwnedFire(sl, ground, touching);
                                check(
                                        "Placed soul fire attributes burns to player",
                                        touching.isOnFire()
                                                && touching.getPersistentData()
                                                        .getBoolean("mm_soul")
                                                && touching.getPersistentData()
                                                        .getUUID("mm_burn_owner")
                                                        .equals(p.getUUID()));
                                var lava = ground.offset(3, 0, 0);
                                sl.setBlockAndUpdate(
                                        lava,
                                        net.minecraft.world.level.block.Blocks.LAVA
                                                .defaultBlockState());
                                OwnedFire.ownLava(sl, lava, p);
                                touching.setPos(
                                        net.minecraft.world.phys.Vec3.atBottomCenterOf(lava));
                                touching.clearFire();
                                OwnedFire.burnFromOwnedLava(sl, touching);
                                check(
                                        "Player lava inflicts owned soul burn",
                                        touching.isOnFire()
                                                && touching.getPersistentData()
                                                        .getBoolean("mm_soul"));
                                touching.discard();
                                var ember =
                                        new com.ethermod.beam.BeamBallEntity(
                                                sl,
                                                p,
                                                0,
                                                ManipulatorStage.NETHERITE,
                                                OrbMode.IGNITE);
                                ember.configure(st);
                                check(
                                        "Soulwake Ignite projectile syncs soul visual",
                                        ember.isSoulFire());
                                var charging =
                                        com.ethermod.registry.ModEntities.SOUL_BLAZELING
                                                .get()
                                                .create(sl);
                                charging.configure(p);
                                charging.setPos(p.position().add(0, 2, 0));
                                charging.setTarget(boltTarget);
                                // A fresh living target in line of sight allows its pre-shot
                                // warning.
                                var warningTarget =
                                        net.minecraft.world.entity.EntityType.HUSK.create(sl);
                                warningTarget.setPos(charging.position().add(0, 0, 5));
                                sl.addFreshEntity(warningTarget);
                                charging.setTarget(warningTarget);
                                charging.tick();
                                check(
                                        "Blazeling charges with soul fire before shot",
                                        charging.isOnFire()
                                                && charging.displayFireAnimation()
                                                && charging.getPersistentData()
                                                        .getBoolean("mm_soul"));
                                charging.fireAt(warningTarget);
                                check(
                                        "Blazeling clears warning after its single shot",
                                        !charging.isOnFire());
                                warningTarget.discard();
                                var routine =
                                        com.ethermod.registry.ModEntities.SOUL_BLAZELING
                                                .get()
                                                .create(sl);
                                routine.configure(p);
                                var hoverFloor = p.blockPosition().offset(0, 30, 0);
                                for (int x = -2; x <= 2; x++)
                                    for (int z = -2; z <= 2; z++)
                                        sl.setBlockAndUpdate(
                                                hoverFloor.offset(x, 0, z),
                                                net.minecraft.world.level.block.Blocks.STONE
                                                        .defaultBlockState());
                                routine.setPos(
                                        net.minecraft.world.phys.Vec3.atBottomCenterOf(
                                                        hoverFloor.above())
                                                .add(
                                                        0,
                                                        com.ethermod.entity.SoulBlazeling
                                                                .HOVER_HEIGHT,
                                                        0));
                                sl.addFreshEntity(routine);
                                var dummy = net.minecraft.world.entity.EntityType.HUSK.create(sl);
                                dummy.setNoAi(true);
                                dummy.setPos(routine.position().add(0, 0, 5));
                                sl.addFreshEntity(dummy);
                                routine.setTarget(dummy);
                                double startY = routine.getY(), peakY = startY;
                                int firedAt = -1;
                                for (int frame = 0; frame < 100; frame++) {
                                    boolean before = routine.isOnFire();
                                    routine.tick();
                                    peakY = Math.max(peakY, routine.getY());
                                    if (before && !routine.isOnFire() && firedAt < 0)
                                        firedAt = frame;
                                }
                                check(
                                        "Blazeling rises before its single shot",
                                        peakY > startY + .5
                                                && peakY < startY + 1.2
                                                && firedAt >= 55
                                                && firedAt <= 65);
                                check(
                                        "Blazeling settles after firing",
                                        routine.getY() < peakY - .4
                                                && routine.getY() > startY - .15
                                                && !routine.isOnFire());
                                check(
                                        "Blazeling routine fires only once in cycle",
                                        sl.getEntitiesOfClass(
                                                                com.ethermod.entity.SoulBolt.class,
                                                                routine.getBoundingBox().inflate(5),
                                                                e -> e.getOwner() == routine)
                                                        .size()
                                                == 1);
                                for (var bolt :
                                        sl.getEntitiesOfClass(
                                                com.ethermod.entity.SoulBolt.class,
                                                routine.getBoundingBox().inflate(5),
                                                e -> e.getOwner() == routine)) bolt.discard();
                                routine.discard();
                                dummy.discard();
                                var idle =
                                        com.ethermod.registry.ModEntities.SOUL_BLAZELING
                                                .get()
                                                .create(sl);
                                idle.configure(p);
                                idle.setPos(
                                        net.minecraft.world.phys.Vec3.atBottomCenterOf(
                                                hoverFloor.above()));
                                sl.addFreshEntity(idle);
                                var ownerBeforeIdle = p.position();
                                p.setPos(
                                        net.minecraft.world.phys.Vec3.atBottomCenterOf(
                                                        hoverFloor.above())
                                                .add(1, 0, 0));
                                // No enemy or following input: idle flight must establish its own
                                // clearance.
                                for (int frame = 0; frame < 40; frame++) idle.tick();
                                check(
                                        "Blazeling idles at raised hover height",
                                        Math.abs(idle.getY() - (hoverFloor.getY() + 1 + .8)) < .12
                                                && !idle.isOnFire());
                                idle.discard();
                                p.setPos(ownerBeforeIdle);
                                check(
                                        "Fire expiry has no cracks but ice retains them",
                                        !TempBlocks.showsExpiryCracks(
                                                        net.minecraft.world.level.block.Blocks
                                                                .SOUL_FIRE
                                                                .defaultBlockState())
                                                && !TempBlocks.showsExpiryCracks(
                                                        net.minecraft.world.level.block.Blocks.FIRE
                                                                .defaultBlockState())
                                                && TempBlocks.showsExpiryCracks(
                                                        net.minecraft.world.level.block.Blocks.ICE
                                                                .defaultBlockState()));
                                var expires =
                                        com.ethermod.registry.ModEntities.SOUL_BLAZELING
                                                .get()
                                                .create(sl);
                                expires.configure(p);
                                expires.setPos(p.position().add(0, 6, 0));
                                sl.addFreshEntity(expires);
                                expires.getPersistentData()
                                        .putLong("mm_summon_expires", sl.getGameTime());
                                expires.tick();
                                check(
                                        "Blazeling expiry dies instead of discarding",
                                        !expires.isAlive() && !expires.isRemoved());
                                var savedPet = new net.minecraft.nbt.CompoundTag();
                                pet.saveWithoutId(savedPet);
                                var loadedPet =
                                        com.ethermod.registry.ModEntities.SOUL_BLAZELING
                                                .get()
                                                .create(sl);
                                loadedPet.load(savedPet);
                                check(
                                        "Blazeling ownership and deadline survive save/load",
                                        loadedPet.summoner() == p
                                                && loadedPet
                                                                .getPersistentData()
                                                                .getLong("mm_summon_expires")
                                                        == pet.getPersistentData()
                                                                .getLong("mm_summon_expires"));
                                TraitBuild.setLine(st, 0, Traits.KINETIC);
                                setPickChecked(st, 0, 0, "kinetic.split");
                                setPickChecked(st, 0, 1, "kinetic.marksman");
                                setPickChecked(st, 0, 2, "kinetic.detonator");
                                TraitBuild.setLine(st, 1, Traits.HYDRAULIC);
                                setPickChecked(st, 1, 0, "hydraulic.pressure");
                                OrbMode.set(st, OrbMode.WATER);
                                OrbMode.setTank(st, 6);
                                var item = com.ethermod.registry.ModItems.ETHER_MANIPULATOR.get();
                                // the water blob is Hydraulic's left click: a full charge
                                com.ethermod.item.EtherManipulatorItem.fireVolley(
                                        sl, p, st, 2, OrbMode.WATER);
                                check(
                                        "Split pays once per whole shot",
                                        OrbMode.tank(st) == 6 - 4);
                                OrbMode.setTank(st, 16);
                                check(
                                        "Retired Gunner/Orbit dropped from old builds",
                                        TraitChoices.resolve(
                                                                "kinetic",
                                                                java.util.List.of(
                                                                        "kinetic.split",
                                                                        "kinetic.gunner",
                                                                        "kinetic.orbit"),
                                                                1)
                                                        .isEmpty()
                                                && !TraitChoices.accepts(
                                                        "kinetic", 2, "kinetic.orbit"));
                                check(
                                        "Seeker is a Kinetic Grandmaster choice",
                                        TraitChoices.accepts("kinetic", 2, "kinetic.seeker"));
                                TraitBuild.setLine(st, 1, Traits.PYROTECHNIC);
                                setPickChecked(st, 1, 0, "pyrotechnic.crucible");
                                TraitBuild.setLine(st, 0, Traits.HYDRAULIC);
                                setPickChecked(st, 0, 0, "hydraulic.reservoir");
                                p.getPersistentData().putInt("mm_refill_ether", 0);
                                Manipulator.sync(p);
                            });
        if (tick == 70) mc.setScreen(new TraitScreen());
        if (tick == 100)
            if (!Boolean.getBoolean("ethermod.noScreenshots"))
                net.minecraft.client.Screenshot.grab(
                        mc.gameDirectory,
                        "redesign-trees.png",
                        mc.getMainRenderTarget(),
                        c -> System.out.println(c.getString()));
        if (tick == 140)
            mc.getSingleplayerServer()
                    .execute(
                            () -> {
                                var p =
                                        mc.getSingleplayerServer()
                                                .getPlayerList()
                                                .getPlayer(mc.player.getUUID());
                                var st = Manipulator.stack(p);
                                check(
                                        "Energy orb spends the shared tank, Tether does not",
                                        OrbMode.ENERGY.usesTank()
                                                && OrbMode.ENERGY.shotCost(st, 2) == 1
                                                && !OrbMode.TETHER.usesTank());
                                TraitBuild.setLine(st, 0, Traits.HYDRAULIC);
                                setPickChecked(st, 0, 0, "hydraulic.reservoir");
                                TraitBuild.setLine(st, 1, Traits.PYROTECHNIC);
                                setPickChecked(st, 1, 0, "pyrotechnic.crucible");
                                TraitBuild.setLine(st, 2, Traits.BOTANIC);
                                setPickChecked(st, 2, 0, "botanic.seedbank");
                                OrbMode.setTank(st, 0);
                                p.getPersistentData().putInt("mm_tank_last", 0);
                                p.getPersistentData()
                                        .putInt(
                                                "mm_refill_ether",
                                                com.ethermod.trait.TankReload.reloadDelay(st)
                                                        - 1);
                                Scheduler.schedule(
                                        p.serverLevel(),
                                        2,
                                        () -> {
                                            check(
                                                    "Shared tank reloads in full after the delay",
                                                    OrbMode.tank(st) == OrbMode.capacity(st));
                                            check(
                                                    "Three tank traits stack",
                                                    OrbMode.capacity(st) == 12);
                                            var over = st.copy();
                                            OrbMode.setTank(over, 999);
                                            check(
                                                    "Tank never exceeds capacity",
                                                    OrbMode.tank(over) == OrbMode.capacity(over));
                                            TraitBuild.setLine(st, 2, Traits.PNEUMATIC);
                                            setPickChecked(st, 2, 0, "pneumatic.bellows");
                                            check(
                                                    "Bellows grows the shared tank",
                                                    OrbMode.capacity(st) == 12);
                                        });
                            });
        if (tick == 185) {
            mc.setScreen(null);
            mc.getSingleplayerServer()
                    .execute(
                            () -> {
                                var p =
                                        mc.getSingleplayerServer()
                                                .getPlayerList()
                                                .getPlayer(mc.player.getUUID());
                                var st = Manipulator.stack(p);
                                TraitBuild.setLine(st, 0, Traits.GRAVITIC);
                                setPickChecked(st, 0, 0, "gravitic.gravity_shift");
                                setPickChecked(st, 0, 1, "gravitic.skyhook");
                                setPickChecked(st, 0, 2, "gravitic.airwalk");
                                OrbMode.set(st, OrbMode.TETHER);
                                for (var dir : net.minecraft.core.Direction.values()) {
                                    var up =
                                            MovementPhysics.rotate(
                                                    new net.minecraft.world.phys.Vec3(0, 1, 0),
                                                    dir);
                                    check(
                                            "Gravity basis " + dir,
                                            up.distanceTo(MovementPhysics.vector(dir.getOpposite()))
                                                    < .0001);
                                    MovementPhysics.setGravity(p, dir);
                                    var hook =
                                            new com.ethermod.beam.BeamBallEntity(
                                                    p.serverLevel(),
                                                    p,
                                                    0,
                                                    ManipulatorStage.NETHERITE,
                                                    OrbMode.TETHER);
                                    hook.configure(st);
                                    check(
                                            "Hook follows aim " + dir,
                                            hook.getDeltaMovement()
                                                            .normalize()
                                                            .dot(p.getLookAngle().normalize())
                                                    > .999);
                                    var at = p.getEyePosition().add(p.getLookAngle().scale(6));
                                    hook.setPos(at);
                                    p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                                    // what letting go of left click (or reaching max range) does
                                    com.ethermod.beam.OrbTether.hookAir(hook);
                                    check(
                                            "Skyhook anchors in the air " + dir,
                                            hook.isStuck()
                                                    && hook.position().distanceToSqr(at) < .00001
                                                    && MovementPhysics.down(p) == dir);
                                    hook.discard();
                                }
                                // Repeatedly standing against a surface must not generate a jump or
                                // a bounce.
                                var savedFeet = p.position();
                                var savedDirection = MovementPhysics.down(p);
                                for (var direction : net.minecraft.core.Direction.values()) {
                                    var wall =
                                            p.blockPosition()
                                                    .offset(35, 12, direction.ordinal() * 8);
                                    var face = direction.getOpposite();
                                    for (int a = -2; a <= 2; a++)
                                        for (int b = -2; b <= 2; b++) {
                                            var cell =
                                                    direction.getAxis()
                                                                    == net.minecraft.core.Direction
                                                                            .Axis.X
                                                            ? wall.offset(0, a, b)
                                                            : direction.getAxis()
                                                                            == net.minecraft.core
                                                                                    .Direction.Axis
                                                                                    .Y
                                                                    ? wall.offset(a, 0, b)
                                                                    : wall.offset(a, b, 0);
                                            p.serverLevel()
                                                    .setBlockAndUpdate(
                                                            cell,
                                                            net.minecraft.world.level.block.Blocks
                                                                    .STONE
                                                                    .defaultBlockState());
                                        }
                                    p.getPersistentData()
                                            .putInt("mm_gravity", direction.get3DDataValue());
                                    p.setNoGravity(direction != net.minecraft.core.Direction.DOWN);
                                    var surface =
                                            net.minecraft.world.phys.Vec3.atCenterOf(wall)
                                                    .add(MovementPhysics.vector(face).scale(.5001));
                                    p.setPos(surface);
                                    p.setOnGround(true);
                                    p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                                    p.setPose(net.minecraft.world.entity.Pose.STANDING);
                                    p.setPos(surface);
                                    p.setShiftKeyDown(false);
                                    p.setSwimming(false);
                                    check(
                                            "Standing pose clearance follows gravity " + direction,
                                            fitsPose(p, net.minecraft.world.entity.Pose.STANDING));
                                    p.setPose(net.minecraft.world.entity.Pose.SWIMMING);
                                    p.setPos(surface);
                                    poseTick(p);
                                    check(
                                            "Ceiling/wall crawl recovers to standing " + direction,
                                            p.getPose()
                                                    == net.minecraft.world.entity.Pose.STANDING);
                                    p.setPose(net.minecraft.world.entity.Pose.STANDING);
                                    p.setPos(surface);
                                    var planted = p.position();
                                    p.setPose(net.minecraft.world.entity.Pose.CROUCHING);
                                    check(
                                            "Pose resize keeps feet planted " + direction,
                                            p.position().distanceToSqr(planted) < .000001);
                                    check(
                                            "Pose resize rebuilds correct box " + direction,
                                            Math.abs(
                                                            p.getBoundingBox().getSize()
                                                                    - MovementPhysics.boundsAt(
                                                                                    p,
                                                                                    p.position(),
                                                                                    direction)
                                                                            .getSize())
                                                    < .00001);
                                    p.setPose(net.minecraft.world.entity.Pose.STANDING);
                                    p.setPos(surface);
                                    p.setOnGround(true);
                                    p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                                    double minimum = Double.MAX_VALUE, maximum = -Double.MAX_VALUE;
                                    boolean stood = true;
                                    for (int step = 0; step < 100; step++) {
                                        poseTick(p);
                                        p.travel(net.minecraft.world.phys.Vec3.ZERO);
                                        stood &=
                                                p.getPose()
                                                        == net.minecraft.world.entity.Pose.STANDING;
                                        double distance =
                                                p.position()
                                                        .subtract(surface)
                                                        .dot(MovementPhysics.vector(face));
                                        minimum = Math.min(minimum, distance);
                                        maximum = Math.max(maximum, distance);
                                    }
                                    check(
                                            "Standing surface with pose ticks remains stable "
                                                    + direction,
                                            stood && maximum - minimum < .005 && p.onGround());
                                    var roof = wall.relative(face, 2);
                                    p.serverLevel()
                                            .setBlockAndUpdate(
                                                    roof,
                                                    net.minecraft.world.level.block.Blocks.STONE
                                                            .defaultBlockState());
                                    poseTick(p);
                                    check(
                                            "Real low clearance still allows crawling " + direction,
                                            p.getPose()
                                                    == net.minecraft.world.entity.Pose.SWIMMING);
                                    p.serverLevel()
                                            .setBlockAndUpdate(
                                                    roof,
                                                    net.minecraft.world.level.block.Blocks.AIR
                                                            .defaultBlockState());
                                    poseTick(p);
                                    check(
                                            "Leaving low clearance restores standing " + direction,
                                            p.getPose()
                                                    == net.minecraft.world.entity.Pose.STANDING);
                                    p.getPersistentData()
                                            .putInt("mm_gravity", savedDirection.get3DDataValue());
                                    p.setNoGravity(
                                            savedDirection != net.minecraft.core.Direction.DOWN);
                                    p.setPos(savedFeet);
                                }
                                MovementPhysics.setGravity(p, net.minecraft.core.Direction.EAST);
                                p.getAbilities().flying = true;
                                p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                                var still = p.position();
                                p.travel(net.minecraft.world.phys.Vec3.ZERO);
                                check(
                                        "Creative flight has no gravity acceleration",
                                        p.getDeltaMovement().lengthSqr() < .000001
                                                && p.position().distanceToSqr(still) < .000001);
                                p.getAbilities().flying = false;
                                var wall = p.blockPosition().offset(4, 0, 0);
                                for (int y = -2; y <= 3; y++)
                                    for (int z = -2; z <= 2; z++)
                                        p.serverLevel()
                                                .setBlockAndUpdate(
                                                        wall.offset(0, y, z),
                                                        net.minecraft.world.level.block.Blocks.STONE
                                                                .defaultBlockState());
                                var swept =
                                        GravityCollision.resolve(
                                                p, new net.minecraft.world.phys.Vec3(8, 0, 0));
                                check(
                                        "Rotated collision sweep stops at terrain",
                                        swept.x < 4
                                                && p.serverLevel()
                                                        .noCollision(
                                                                p,
                                                                p.getBoundingBox()
                                                                        .move(swept)
                                                                        .deflate(.000001)));
                                MovementPhysics.setGravity(p, net.minecraft.core.Direction.DOWN);
                                var safe = p.position();
                                var shaft = p.blockPosition().offset(18, 8, 0);
                                for (int y = 0; y <= 5; y++)
                                    for (int x = -1; x <= 1; x++)
                                        for (int z = -1; z <= 1; z++)
                                            if (x != 0 || z != 0 || y == 0 || y == 5)
                                                p.serverLevel()
                                                        .setBlockAndUpdate(
                                                                shaft.offset(x, y, z),
                                                                net.minecraft.world.level.block
                                                                        .Blocks.STONE
                                                                        .defaultBlockState());
                                p.setPos(shaft.getX() + .5, shaft.getY() + 1, shaft.getZ() + .5);
                                var beforeTurn = p.position();
                                check(
                                        "Cramped gravity turn refuses instead of clipping",
                                        !MovementPhysics.setGravity(
                                                        p, net.minecraft.core.Direction.EAST)
                                                && p.position().distanceToSqr(beforeTurn) < .000001
                                                && MovementPhysics.down(p)
                                                        == net.minecraft.core.Direction.DOWN);
                                p.setPos(safe);
                                var pos = p.position();
                                p.teleportTo(
                                        p.serverLevel(),
                                        pos.x,
                                        pos.y + 8,
                                        pos.z,
                                        java.util.Set.of(),
                                        p.getYRot(),
                                        p.getXRot());
                                MovementPhysics.setGravity(p, net.minecraft.core.Direction.EAST);
                                Manipulator.sync(p);
                                Scheduler.schedule(
                                        p.serverLevel(),
                                        5,
                                        () -> {
                                            var orientation = MovementPhysics.viewRotation(p, 0);
                                            var up =
                                                    orientation.transform(
                                                            new org.joml.Vector3f(0, 1, 0));
                                            check(
                                                    "Gravity turn is visibly intermediate after"
                                                        + " five ticks",
                                                    up.x < -.2 && up.y > .2);
                                        });
                            });
        }
        if (tick == 205)
            mc.getSingleplayerServer()
                    .execute(
                            () -> {
                                var p =
                                        mc.getSingleplayerServer()
                                                .getPlayerList()
                                                .getPlayer(mc.player.getUUID());
                                noCrackFire = p.blockPosition().offset(4, 4, 4);
                                p.serverLevel()
                                        .setBlockAndUpdate(
                                                noCrackFire.below(),
                                                net.minecraft.world.level.block.Blocks.STONE
                                                        .defaultBlockState());
                                TempBlocks.tempBlock(
                                        p.serverLevel(),
                                        noCrackFire,
                                        net.minecraft.world.level.block.Blocks.SOUL_FIRE
                                                .defaultBlockState(),
                                        40);
                            });
        if (tick == 214) {
            try {
                var f =
                        net.minecraft.client.renderer.LevelRenderer.class.getDeclaredField(
                                "destroyingBlocks");
                f.setAccessible(true);
                var blocks =
                        (it.unimi.dsi.fastutil.ints.Int2ObjectMap<
                                        net.minecraft.server.level.BlockDestructionProgress>)
                                f.get(mc.levelRenderer);
                check(
                        "Soul fire sends no mining-crack overlay",
                        noCrackFire != null
                                && mc.level
                                        .getBlockState(noCrackFire)
                                        .is(net.minecraft.world.level.block.Blocks.SOUL_FIRE)
                                && blocks.values().stream()
                                        .noneMatch(
                                                progress -> progress.getPos().equals(noCrackFire)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            var root = com.ethermod.client.BlazelingModel.layer().bakeRoot();
            var model = new com.ethermod.client.BlazelingModel(root);
            model.setupAnim(null, 0, 0, 0, 0, 0);
            check("Blazeling model has one head and three rods", root.getAllParts().count() == 5);
            var rod = root.getChild("rod_0");
            float x = rod.x, z = rod.z;
            model.setupAnim(null, 0, 0, 10, 30, 15);
            check(
                    "Blazeling rods orbit at preserved radius",
                    Math.abs(Math.hypot(rod.x, rod.z) - Math.sqrt(36.5)) < .0001
                            && Math.hypot(rod.x - x, rod.z - z) > .1);
            check(
                    "Blazeling rods sit below face centre",
                    root.getChild("rod_0").y - root.getChild("head").y > 2.4f);
            check(
                    "Blazeling head tracks independently",
                    Math.abs(root.getChild("head").yRot - Math.PI / 6) < .0001);
            var testPet = com.ethermod.registry.ModEntities.SOUL_BLAZELING.get().create(mc.level);
            check(
                    "Blazeling renderer registered",
                    mc.getEntityRenderDispatcher().getRenderer(testPet)
                            instanceof com.ethermod.client.BlazelingRenderer);
        }
        if (tick == 215) {
            var player = mc.player;
            var camera = mc.gameRenderer.getMainCamera();
            System.out.println(
                    "GRAVITY VISUAL player="
                            + player.position()
                            + " eye="
                            + player.getEyePosition()
                            + " camera="
                            + camera.getPosition()
                            + " block="
                            + mc.level.getBlockState(
                                    net.minecraft.core.BlockPos.containing(camera.getPosition()))
                            + " clear="
                            + mc.level.noCollision(player, player.getBoundingBox())
                            + " box="
                            + player.getBoundingBox()
                            + " eyeY="
                            + player.getEyeY());
            if (!Boolean.getBoolean("ethermod.noScreenshots"))
                net.minecraft.client.Screenshot.grab(
                        mc.gameDirectory, "gravity-frame.png", mc.getMainRenderTarget(), c -> {});
            check(
                    "Client gravity received",
                    MovementPhysics.down(mc.player) == net.minecraft.core.Direction.EAST);
            check(
                    "Client gravity eye offset",
                    mc.player.getEyePosition().subtract(mc.player.position()).x < -.5);
            try {
                var method =
                        net.minecraft.client.renderer.ScreenEffectRenderer.class.getDeclaredMethod(
                                "getOverlayBlock", net.minecraft.world.entity.player.Player.class);
                method.setAccessible(true);
                check(
                        "Gravity overlay follows the actual eye",
                        method.invoke(null, mc.player) == null);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
        if (tick == 216) {
            var p = mc.player;
            var saved = p.position();
            var velocity = p.getDeltaMovement();
            var gravity = MovementPhysics.down(p);
            var pose = p.getPose();
            boolean flying = p.getAbilities().flying;
            float yaw = p.getYRot();
            try {
                var escape =
                        net.minecraft.client.player.LocalPlayer.class.getDeclaredMethod(
                                "moveTowardsClosestSpace", double.class, double.class);
                escape.setAccessible(true);
                for (var down : net.minecraft.core.Direction.values()) {
                    var wall = net.minecraft.core.BlockPos.containing(saved).offset(0, 12, 0);
                    var old = mc.level.getBlockState(wall);
                    mc.level.setBlock(
                            wall,
                            net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                            3);
                    var up = MovementPhysics.vector(down.getOpposite());
                    var feet = net.minecraft.world.phys.Vec3.atCenterOf(wall).add(up.scale(.5));
                    p.getPersistentData().putInt("mm_gravity", down.get3DDataValue());
                    MovementPhysics.beginTurn(p, down, p.level().getGameTime() - 20);
                    p.setPose(net.minecraft.world.entity.Pose.STANDING);
                    p.setPos(feet);
                    p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                    p.getAbilities().flying = false;
                    for (int x : new int[] {-1, 1})
                        for (int z : new int[] {-1, 1})
                            escape.invoke(
                                    p,
                                    p.getX() + x * p.getBbWidth() * .35,
                                    p.getZ() + z * p.getBbWidth() * .35);
                    check(
                            "Client escape leaves supported player stable " + down,
                            p.getDeltaMovement().lengthSqr() < 1e-8);
                    if (down != net.minecraft.core.Direction.DOWN) {
                        p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                        boolean stable = true;
                        for (int frame = 0; frame < 100; frame++) {
                            for (int x : new int[] {-1, 1})
                                for (int z : new int[] {-1, 1})
                                    escape.invoke(
                                            p,
                                            p.getX() + x * p.getBbWidth() * .35,
                                            p.getZ() + z * p.getBbWidth() * .35);
                            poseTick(p);
                            p.travel(net.minecraft.world.phys.Vec3.ZERO);
                            stable &= p.position().distanceToSqr(feet) < .00001;
                        }
                        check("Client wall escape and travel stay planted " + down, stable);
                    }
                    if (down != net.minecraft.core.Direction.DOWN) {
                        p.xo = p.getX();
                        p.yo = p.getY();
                        p.zo = p.getZ();
                        p.setYRot(0);
                        p.yRotO = 0;
                        p.setXRot(0);
                        p.xRotO = 0;
                        var walker = new WalkProbe(mc.level);
                        walker.getPersistentData().putInt("mm_gravity", down.get3DDataValue());
                        for (var along :
                                new net.minecraft.world.phys.Vec3[] {
                                    new net.minecraft.world.phys.Vec3(.12, 0, 0),
                                    new net.minecraft.world.phys.Vec3(0, 0, .12)
                                }) {
                            walker.setPos(feet);
                            walker.step = MovementPhysics.rotate(along, down);
                            float facing = along.x > 0 ? -90 : 0;
                            walker.setYRot(facing);
                            walker.yBodyRot = facing + 45;
                            for (int n = 0; n < 12; n++) {
                                walker.xo = walker.getX();
                                walker.yo = walker.getY();
                                walker.zo = walker.getZ();
                                walker.tick();
                            }
                            check(
                                    "Body facing follows local walk " + down + " along=" + along,
                                    Math.abs(
                                                            net.minecraft.util.Mth.wrapDegrees(
                                                                    walker.yBodyRot - facing))
                                                    < 5
                                            && walker.walkAnimation.speed() > .1);
                        }
                        var eye = MovementPhysics.eye(p, 0);
                        for (boolean front : new boolean[] {false, true}) {
                            var cam = new net.minecraft.client.Camera();
                            cam.setup(mc.level, p, true, front, 0);
                            check(
                                    "Third person stays outside head " + down + " front=" + front,
                                    cam.getPosition().distanceTo(eye) > 3.5);
                        }
                        var edgeMethod =
                                net.minecraft.world.entity.player.Player.class.getDeclaredMethod(
                                        "maybeBackOffFromEdge",
                                        net.minecraft.world.phys.Vec3.class,
                                        net.minecraft.world.entity.MoverType.class);
                        edgeMethod.setAccessible(true);
                        p.setShiftKeyDown(true);
                        p.input.shiftKeyDown = true;
                        p.setOnGround(true);
                        var wanted =
                                MovementPhysics.rotate(
                                        new net.minecraft.world.phys.Vec3(1.5, -.0784, 0), down);
                        var limited =
                                (net.minecraft.world.phys.Vec3)
                                        edgeMethod.invoke(
                                                p,
                                                wanted,
                                                net.minecraft.world.entity.MoverType.SELF);
                        check(
                                "Sneak protects gravity-relative ledge " + down,
                                limited.subtract(up.scale(limited.dot(up))).length() < .81);
                        p.setShiftKeyDown(false);
                        p.input.shiftKeyDown = false;
                        var st = Manipulator.stack(p);
                        var original =
                                st.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                        boolean key = mc.options.keyJump.isDown();
                        try {
                            for (int slot = 0; slot < 3; slot++) TraitBuild.setLine(st, slot, null);
                            TraitBuild.setLine(st, 0, Traits.PNEUMATIC);
                            setPickChecked(st, 0, 0, "pneumatic.gale_force");
                            mc.options.keyJump.setDown(false);
                            p.setOnGround(true);
                            p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                            ClientMobility.tick(
                                    new net.neoforged.neoforge.client.event.ClientTickEvent.Post());
                            p.setOnGround(false);
                            p.setDeltaMovement(up.scale(.42));
                            mc.options.keyJump.setDown(true);
                            ClientMobility.tick(
                                    new net.neoforged.neoforge.client.event.ClientTickEvent.Post());
                            check(
                                    "Pneumatic Common preserves jump away from surface " + down,
                                    p.getDeltaMovement().dot(up) > .35);
                            p.setDeltaMovement(up.scale(-.5));
                            ClientMobility.tick(
                                    new net.neoforged.neoforge.client.event.ClientTickEvent.Post());
                            check(
                                    "Pneumatic Common slows local falling " + down,
                                    Math.abs(p.getDeltaMovement().dot(up) + .08) < .00001);
                        } finally {
                            if (original == null)
                                st.remove(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                            else
                                st.set(
                                        net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                                        original);
                            mc.options.keyJump.setDown(key);
                            p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                        }
                    }
                    p.walkAnimation.update(0, 1);
                    p.xo = p.getX();
                    p.yo = p.getY();
                    p.zo = p.getZ();
                    p.setYRot(0);
                    p.setOnGround(true);
                    p.setDeltaMovement(
                            MovementPhysics.rotate(
                                    new net.minecraft.world.phys.Vec3(.05, 0, 0), down));
                    p.travel(net.minecraft.world.phys.Vec3.ZERO);
                    if (down != net.minecraft.core.Direction.DOWN)
                        check(
                                "Gravity travel advances walking animation " + down,
                                p.walkAnimation.speed() > .01);
                    if (down != net.minecraft.core.Direction.DOWN) {
                        p.walkAnimation.update(0, 1);
                        p.xo = p.getX() - up.x * .1;
                        p.yo = p.getY() - up.y * .1;
                        p.zo = p.getZ() - up.z * .1;
                        p.calculateEntityAnimation(false);
                        check(
                                "Gravity normal motion does not walk " + down,
                                p.walkAnimation.speed() < .0001);
                    }
                    mc.level.setBlock(wall, old, 3);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                p.getPersistentData().putInt("mm_gravity", gravity.get3DDataValue());
                MovementPhysics.beginTurn(p, gravity, p.level().getGameTime() - 20);
                p.setPose(pose);
                p.setPos(saved);
                p.setDeltaMovement(velocity);
                p.getAbilities().flying = flying;
                p.setYRot(yaw);
            }
        }
        if (tick == 225)
            mc.getSingleplayerServer()
                    .execute(
                            () -> {
                                var p =
                                        mc.getSingleplayerServer()
                                                .getPlayerList()
                                                .getPlayer(mc.player.getUUID());
                                MovementPhysics.setGravity(p, net.minecraft.core.Direction.DOWN);
                            });
        if (tick == 260) {
            try {
                Files.write(Path.of("redesign-probe-report.txt"), report);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (!Boolean.getBoolean("ethermod.uiInteractive")) mc.stop();
        }
    }
}
