package com.ethermod.item;

import com.ethermod.beam.BeamBallEntity;
import com.ethermod.client.ManipulatorClientExtensions;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.manipulator.ManipulatorData;
import com.ethermod.trait.TraitBuild;
import com.ethermod.trait.Traits;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.animatable.SingletonGeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.List;
import java.util.function.Consumer;

/**
 * The Ether Manipulator — a BEAM-only GeckoLib item.
 *
 * <p>LMB held → continuous beam (client → BeamActivePayload → server) RMB held → charge beam ball
 * (vanilla use-item system; bow animation in 3rd person) Release fires the ball. Phase depends on
 * how long RMB was held.
 *
 * <p>Never lives in the inventory: each player owns one in its own slot (see Manipulator /
 * PlayerMixin). A stack that ends up in an inventory (old saves, /give) is folded into that slot
 * and removed.
 *
 * <p>Implements GeoItem so GeckoLib can animate the model and so the "muzzle_anchor" bone pivot can
 * be captured each frame by ManipulatorItemRenderer.
 */
public class EtherManipulatorItem extends Item implements GeoItem {

    private final AnimatableInstanceCache animCache = GeckoLibUtil.createInstanceCache(this);

    public EtherManipulatorItem() {
        super(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));

        // Register for server-side animation syncing (required by GeoItem)
        SingletonGeoAnimatable.registerSyncedAnimatable(this);
    }

    // =========================================================================
    // GeoItem — animation instance cache + controllers
    // =========================================================================

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.animCache;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Keyframed prong animations, picked on the client from what the holder is doing.
        // Charge shake is part of the animations (first person) and the arm pose (third person).
        if (FMLEnvironment.dist.isClient()) {
            controllers.add(com.ethermod.client.ManipulatorAnimations.controller(this));
        }
    }

    // =========================================================================
    // Client extensions — GeckoLib renderer + 3rd person bow arm pose
    //
    // Using initializeClient (deprecated) rather than createGeoRenderer because:
    //  - We also need getArmPose() from IClientItemExtensions (bow draw pose)
    //  - Both can coexist in one initializeClient call
    //  - This has been verified to work reliably on NeoForge 1.21.1
    // =========================================================================

    @SuppressWarnings("removal")
    @Override
    @OnlyIn(Dist.CLIENT)
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        // ManipulatorClientExtensions.INSTANCE implements:
        //   - getCustomRenderer()        → GeckoLib ManipulatorItemRenderer
        //   - getArmPose()               → BOW_AND_ARROW while beam/charge active
        //   - applyForgeHandTransform()  → snapshots hand matrix for muzzle capture
        // All three must be on the SAME registered instance.
        consumer.accept(ManipulatorClientExtensions.INSTANCE);
    }

    // =========================================================================
    // RMB — charge + fire beam ball
    // =========================================================================

    @Override
    public InteractionResultHolder<ItemStack> use(
            Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResultHolder.pass(stack);
        // Detonator: right click again while your right-click shot is in the air bursts it
        if (com.ethermod.beam.BeamBallEntity.detonateFor(player, stack, false))
            return InteractionResultHolder.fail(stack);

        OrbMode mode = OrbMode.get(stack);
        // the projectile this tool charges on right click (Hydraulic: the ice shard)
        OrbMode shot = mode.rmbShot();
        // Golem mode: right click summons the Golem, uses its Super Move, or (sneaking) recalls it
        if (mode == OrbMode.GOLEM) {
            if (player instanceof ServerPlayer sp) com.ethermod.entity.GolemControl.rightClick(sp);
            return InteractionResultHolder.fail(stack);
        }
        if (shot.usesTank() && shot.tankLevel(stack) < shot.shotCost(stack, 0)) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("message.ethermod.tank_refilling", mode.title())
                                .withStyle(ChatFormatting.RED),
                        true);
            }
            return InteractionResultHolder.fail(stack);
        }

        if (mode == OrbMode.MASS) {
            // Mass Driver: LMB grabs, RMB charges a throw of what you carry
            if (level.isClientSide
                    ? com.ethermod.beam.MassDriver.carried(stack).isAir()
                    : !(player instanceof ServerPlayer sp
                            && com.ethermod.beam.MassDriver.holding(sp)))
                return InteractionResultHolder.fail(stack);
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(stack);
        }
        // Beam mode: the crystal keeps the colour of the last thing you did (mine or charge)
        if (mode == OrbMode.ENERGY && player instanceof ServerPlayer sp)
            ManipulatorColors.setLastOrb(sp, true);

        // Tether: left click fires the hook; right click reels it in (tap: all the way, hold: while
        // held, double tap: Blink / Gravity Shift). With no hook out, it rides a Zipline.
        if (mode == OrbMode.TETHER) {
            if (player instanceof ServerPlayer sp) {
                var hook = com.ethermod.beam.OrbTether.activeHook(sp);
                if (hook != null) {
                    if (com.ethermod.beam.OrbTether.press(hook, sp))
                        return InteractionResultHolder.fail(stack);
                } else if (sp.isShiftKeyDown() || !com.ethermod.trait.Ziplines.tryRide(sp))
                    return InteractionResultHolder.fail(stack);
            }
            // (the client can't see the Ziplines it might ride: it holds, the server decides)
        }

        // Start charging the orb (or holding the cone / the rope)
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    /**
     * A Manipulator stack sitting in a real inventory slot isn't the player's Manipulator. Adopt it
     * if the player's own is still untouched (migrates pre-attachment saves), then delete it.
     */
    @Override
    public void inventoryTick(
            ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide || !(entity instanceof ServerPlayer sp)) return;
        ManipulatorData data = Manipulator.data(sp);
        ItemStack own = data.stack();
        boolean ownUntouched =
                ManipulatorStage.get(own) == ManipulatorStage.STONE && TraitBuild.isEmpty(own);
        boolean incomingProgressed =
                ManipulatorStage.get(stack) != ManipulatorStage.STONE || !TraitBuild.isEmpty(stack);
        if (ownUntouched && incomingProgressed) {
            data.setStack(stack.copyWithCount(1));
            sp.displayClientMessage(
                    Component.translatable("message.ethermod.moved_to_slot")
                            .withStyle(ChatFormatting.AQUA),
                    false);
        }
        sp.getInventory().setItem(slot, ItemStack.EMPTY);
        Manipulator.sync(sp);
    }

    /** 72000 ticks — never auto-fires; player always releases manually. */
    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    /** Orbit: the orb circling each player right now, by player. Server-side. */
    private static final java.util.Map<java.util.UUID, Integer> ORBITING =
            new java.util.HashMap<>();

    /** Hold modes work every tick while RMB is down. No speed penalty while charging. */
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (level.isClientSide || !(entity instanceof ServerPlayer sp)) return;
        OrbMode mode = OrbMode.get(stack);
        int held = getUseDuration(stack, entity) - remaining;
        var movement = sp.getPersistentData();
        double moved =
                sp.position()
                        .distanceToSqr(
                                new net.minecraft.world.phys.Vec3(
                                        movement.getDouble("mm_charge_x"),
                                        movement.getDouble("mm_charge_y"),
                                        movement.getDouble("mm_charge_z")));
        movement.putInt(
                "mm_charge_still",
                held > 1 && moved < .0004 ? movement.getInt("mm_charge_still") + 1 : 0);
        movement.putDouble("mm_charge_x", sp.getX());
        movement.putDouble("mm_charge_y", sp.getY());
        movement.putDouble("mm_charge_z", sp.getZ());
        if (mode == OrbMode.VACUUM) {
            com.ethermod.beam.VacuumCone.tick(sp, stack, held);
            return;
        }
        if (mode == OrbMode.TETHER) {
            com.ethermod.trait.Ziplines.rideTick(sp);
            return;
        }
    }

    /** Builds, configures and spawns one orb with the Orb badge's damage. */
    private static BeamBallEntity spawnOrb(
            Level level, Player player, ItemStack stack, int phase, OrbMode mode) {
        return spawnOrb(level, player, stack, phase, mode, null);
    }

    private static BeamBallEntity spawnOrb(
            Level level,
            Player player,
            ItemStack stack,
            int phase,
            OrbMode mode,
            net.minecraft.world.level.block.state.BlockState carried) {
        ManipulatorStage stage = ManipulatorStage.get(stack);
        float chargeScale = stage.ballDamage(phase) / stage.ballBaseDamage();
        BeamBallEntity ball = new BeamBallEntity(level, player, phase, stage, mode);
        if (carried != null) ball.setCarried(carried);
        ball.configure(stack);
        if (phase == 2) ball.addBonusDamage(Traits.orbDamageBonus(stack));
        level.addFreshEntity(ball);
        return ball;
    }

    /**
     * RMB released — fire the ball. Phase is determined by how long RMB was held vs the stage's
     * maxChargeTicks.
     */
    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeCharged) {
        if (level.isClientSide) return;
        if (!(entity instanceof Player player)) return;

        ManipulatorStage stage = ManipulatorStage.get(stack);
        int ticksHeld = getUseDuration(stack, entity) - timeCharged;

        OrbMode mode = OrbMode.get(stack);
        if (mode.isHold()) {
            if (player instanceof ServerPlayer sp) {
                com.ethermod.trait.Ziplines.stopRide(sp);
                // Tether: a tap reels all the way in, a hold stopped reeling at its length
                if (mode == OrbMode.TETHER) {
                    var hook = com.ethermod.beam.OrbTether.activeHook(sp);
                    if (hook != null) com.ethermod.beam.OrbTether.release(hook, sp, ticksHeld);
                }
            }
            return;
        }

        int phase = Math.min(2, ticksHeld * 3 / Traits.chargeTicks(stack));
        if (mode == OrbMode.MASS) {
            if (player instanceof ServerPlayer sp) com.ethermod.beam.MassDriver.launch(sp, phase);
            return;
        }
        fireVolley(level, player, stack, phase, mode.rmbShot());
    }

    /** Fires one charged shot (or a Split / Gunner volley) of {@code mode}'s projectile. */
    public static void fireVolley(
            Level level, Player player, ItemStack stack, int charged, OrbMode mode) {
        fireVolley(level, player, stack, charged, mode, false);
    }

    /** As above; {@code left}: fired with left click (Detonator bursts it with left click). */
    public static void fireVolley(
            Level level, Player player, ItemStack stack, int charged, OrbMode mode, boolean left) {
        if (mode == OrbMode.MASS) return; // Persistent Mass Driver handles its own release.
        // Short on tank: the shot drops to the strongest charge you can still pay for
        int affordable = charged;
        while (affordable > 0
                && mode.usesTank()
                && mode.tankLevel(stack) < mode.shotCost(stack, affordable)) affordable--;
        final int phase = affordable;
        int cost = mode.shotCost(stack, phase);
        if (mode.usesTank() && mode.tankLevel(stack) < cost) {
            player.displayClientMessage(
                    Component.translatable("message.ethermod.not_enough_ether")
                            .withStyle(ChatFormatting.RED),
                    true);
            return;
        }
        int count = Traits.has(stack, "kinetic.split") ? phase + 1 : 1;
        int bursts = Traits.has(stack, "kinetic.gunner") ? phase + 1 : 1;
        if (Traits.has(stack, "kinetic.orbit")
                && level.getEntitiesOfClass(
                                        BeamBallEntity.class,
                                        player.getBoundingBox().inflate(4),
                                        e -> e.getOwner() == player && e.isOrbiting())
                                .size()
                        >= 3) return;
        if (mode.usesTank()) {
            mode.setTankLevel(stack, mode.tankLevel(stack) - cost);
            if (player instanceof ServerPlayer sp) Manipulator.sync(sp);
        }
        final int shots = count, rounds = bursts;
        final ItemStack launchTraits = stack.copy();
        for (int round = 0; round < bursts; round++) {
            final int sequence = round;
            Runnable shot =
                    () -> {
                        if (!player.isAlive() || player.level() != level) return;
                        for (int n = 0; n < shots; n++) {
                            if (Traits.has(launchTraits, "kinetic.orbit")
                                    && level.getEntitiesOfClass(
                                                            BeamBallEntity.class,
                                                            player.getBoundingBox().inflate(4),
                                                            e ->
                                                                    e.getOwner() == player
                                                                            && e.isOrbiting())
                                                    .size()
                                            >= 3) break;
                            var orb = spawnOrb(level, player, launchTraits, phase, mode);
                            orb.setFiredLeft(left);
                            orb.scaleDamage(1f / (float) (shots * rounds));
                            if (shots > 1) orb.turn((n - (shots - 1) / 2f) * 10f);
                            if (Traits.has(launchTraits, "kinetic.orbit")) orb.startOrbit();
                            else if (n == 0
                                    && sequence == 0
                                    && mode == OrbMode.WATER
                                    && phase == 2
                                    && Traits.has(launchTraits, "hydraulic.tidecaller"))
                                orb.carryOwner();
                        }
                        level.playSound(
                                null,
                                player.getX(),
                                player.getEyeY(),
                                player.getZ(),
                                net.minecraft.sounds.SoundEvents.BREEZE_SHOOT,
                                net.minecraft.sounds.SoundSource.PLAYERS,
                                0.6f,
                                1.5f - 0.2f * phase);
                    };
            if (sequence == 0) shot.run();
            else
                com.ethermod.trait.WorldEffects.schedule(
                        (net.minecraft.server.level.ServerLevel) level, sequence * 4, shot);
        }
        if (mode == OrbMode.GUST) {
            if (player.onGround() || !player.getPersistentData().getBoolean("mm_gust_boosted")) {
                var recoil = player.getLookAngle().scale(-(0.3 + 0.3 * phase));
                player.setDeltaMovement(player.getDeltaMovement().add(recoil));
                player.hurtMarked = true;
                player.getPersistentData().putBoolean("mm_gust_boosted", true);
            }
        }
    }

    // =========================================================================
    // Tool behaviour (beam replaces pickaxe/axe/shovel)
    // =========================================================================

    @Override
    public boolean canPerformAction(ItemStack stack, ItemAbility ability) {
        return ability == ItemAbilities.PICKAXE_DIG
                || ability == ItemAbilities.AXE_DIG
                || ability == ItemAbilities.SHOVEL_DIG
                || ability == ItemAbilities.HOE_DIG
                || ability == ItemAbilities.SWORD_DIG
                // the sneak beam's jobs (UtilityBeam): NeoForge only changes a block
                // for a tool that says it can do the action
                || ability == ItemAbilities.HOE_TILL
                || ability == ItemAbilities.SHOVEL_FLATTEN
                || ability == ItemAbilities.AXE_STRIP
                || ability == ItemAbilities.AXE_SCRAPE
                || ability == ItemAbilities.AXE_WAX_OFF;
    }

    @Override
    public float getDestroySpeed(
            ItemStack stack, net.minecraft.world.level.block.state.BlockState state) {
        float baseSpeed = ManipulatorStage.get(stack).miningSpeed();
        float skillBonus = Traits.miningSpeedBonus(stack);
        return baseSpeed + skillBonus;
    }

    /** Harvest level follows the stage: Stone can't take diamond ore, Iron can't take obsidian. */
    @Override
    public boolean isCorrectToolForDrops(
            ItemStack stack, net.minecraft.world.level.block.state.BlockState state) {
        return ManipulatorStage.get(stack).canHarvest(state);
    }

    // =========================================================================
    // Tooltip
    // =========================================================================

    @Override
    public void appendHoverText(
            ItemStack stack, TooltipContext ctx, List<Component> tooltip, TooltipFlag flag) {
        ManipulatorStage stage = ManipulatorStage.get(stack);
        tooltip.add(
                stage.next() != null
                        ? Component.translatable(
                                        "tooltip.ethermod.stage_next",
                                        stage.title().withStyle(stageColor(stage)),
                                        stage.next().title())
                                .withStyle(ChatFormatting.GRAY)
                        : Component.translatable("tooltip.ethermod.max_stage", stage.title())
                                .withStyle(ChatFormatting.GOLD));
        OrbMode mode = OrbMode.get(stack);
        tooltip.add(
                Component.translatable(
                                "tooltip.ethermod.mode_ether",
                                mode.title(),
                                mode.tankLevel(stack),
                                mode.tankCapacity(stack))
                        .withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(
                Component.translatable("tooltip.ethermod.traits", TraitBuild.openSlots(stack))
                        .withStyle(ChatFormatting.GREEN));
    }

    private static ChatFormatting stageColor(ManipulatorStage s) {
        return switch (s) {
            case STONE -> ChatFormatting.GRAY;
            case IRON -> ChatFormatting.WHITE;
            case DIAMOND -> ChatFormatting.AQUA;
            case NETHERITE -> ChatFormatting.DARK_PURPLE;
        };
    }

    // =========================================================================
    // Misc
    // =========================================================================

    /**
     * The tank, signal strength and other per-use data live on the stack, so it changes all the
     * time while held. Only a different item (or slot) replays the vanilla equip motion.
     */
    @Override
    public boolean shouldCauseReequipAnimation(
            ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || oldStack.getItem() != newStack.getItem();
    }

    /** Same for mining: a tank top-up mid-dig must not restart the block's crack progress. */
    @Override
    public boolean shouldCauseBlockBreakReset(ItemStack oldStack, ItemStack newStack) {
        return oldStack.getItem() != newStack.getItem();
    }

    @Override
    public boolean isDamageable(ItemStack stack) {
        return false;
    }

    /** Trait-granted enchantments (Fortune, Silk Touch, Looting) shouldn't make it glow. */
    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }

    // Traits replace enchanting entirely.
    @Override
    public boolean isEnchantable(ItemStack stack) {
        return false;
    }

    @Override
    public int getEnchantmentValue() {
        return 0;
    }

    @Override
    public boolean supportsEnchantment(
            ItemStack stack,
            net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> ench) {
        return false;
    }

    /** It can't be thrown or dropped as an entity; it always belongs to its owner. */
    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }
}
