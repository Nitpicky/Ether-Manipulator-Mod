package com.ethermod.item;

import com.ethermod.SideHooks;
import com.ethermod.beam.BeamBallEntity;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.manipulator.ManipulatorData;
import com.ethermod.trait.TraitBuild;
import com.ethermod.trait.Traits;

import net.minecraft.world.InteractionResult;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;

import com.geckolib.animatable.GeoItem;
import com.geckolib.animatable.SingletonGeoAnimatable;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;

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
        // (client only: a server's SideHooks has no controller)
        var controller = SideHooks.get().manipulatorController(this);
        if (controller != null) controllers.add(controller);
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
        consumer.accept(SideHooks.get().manipulatorExtensions());
    }

    // =========================================================================
    // RMB — charge + fire beam ball
    // =========================================================================

    @Override
    public InteractionResult use(
            Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        // Detonator: right click again while your right-click shot is in the air bursts it
        if (com.ethermod.beam.BeamBallEntity.detonateFor(player, stack, false))
            return InteractionResult.FAIL;

        OrbMode mode = OrbMode.get(stack);
        // Pneumatic: right click throws the Stasis Grenade (the Gust shot charges on left click)
        if (mode == OrbMode.GUST) {
            if (player instanceof ServerPlayer sp) com.ethermod.beam.LeftClick.throwGrenade(sp);
            else SideHooks.get().grenadeThrow(player);
            return InteractionResult.FAIL;
        }
        // the projectile this tool charges on right click (Hydraulic: the ice shard)
        OrbMode shot = mode.rmbShot();
        // the tank lives on the Manipulator's own stack (synced to the client), not on the copy in
        // hand, which can be stale on the client and let an empty tank start a charge
        if (shot.isDry(player)) {
            if (player instanceof net.minecraft.server.level.ServerPlayer sp) com.ethermod.beam.DryFire.signal(sp);
            return InteractionResult.FAIL;
        }

        if (mode == OrbMode.MASS) {
            // Mass Driver: LMB grabs, RMB charges a throw of what you carry
            if (level.isClientSide()
                    ? com.ethermod.beam.MassDriver.carried(stack).isAir()
                    : !(player instanceof ServerPlayer sp
                            && com.ethermod.beam.MassDriver.holding(sp)))
                return InteractionResult.FAIL;
            player.startUsingItem(hand);
            return InteractionResult.CONSUME;
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
                        return InteractionResult.FAIL;
                } else {
                    // no hook out: a double tap while gravity is shifted puts it back to normal
                    var data = sp.getPersistentData();
                    long now = sp.level().getGameTime();
                    boolean shifted =
                            com.ethermod.trait.MovementPhysics.down(sp)
                                    != net.minecraft.core.Direction.DOWN;
                    if (shifted && now - data.getLongOr("mm_tether_tap", 0L) <= com.ethermod.beam.OrbTether.DOUBLE_TAP) {
                        data.remove("mm_tether_tap");
                        com.ethermod.trait.MovementPhysics.setGravity(sp, net.minecraft.core.Direction.DOWN);
                        return InteractionResult.FAIL;
                    }
                    data.putLong("mm_tether_tap", now);
                    if (sp.isShiftKeyDown() || !com.ethermod.trait.Ziplines.tryRide(sp))
                        return InteractionResult.FAIL;
                }
            }
            // (the client can't see the Ziplines it might ride: it holds, the server decides)
        }

        // Start charging the orb (or holding the cone / the rope)
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    /**
     * A Manipulator stack sitting in a real inventory slot isn't the player's Manipulator. Adopt it
     * if the player's own is still untouched (migrates pre-attachment saves), then delete it.
     */
    @Override
    public void inventoryTick(
            ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (level.isClientSide() || !(entity instanceof ServerPlayer sp)) return;
        ManipulatorData data = Manipulator.data(sp);
        ItemStack own = data.stack();
        boolean ownUntouched =
                ManipulatorStage.get(own) == ManipulatorStage.STONE && TraitBuild.isEmpty(own);
        boolean incomingProgressed =
                ManipulatorStage.get(stack) != ManipulatorStage.STONE || !TraitBuild.isEmpty(stack);
        if (ownUntouched && incomingProgressed) {
            data.setStack(stack.copyWithCount(1));
            sp.sendSystemMessage(
                    Component.translatable("message.ethermod.moved_to_slot")
                            .withStyle(ChatFormatting.AQUA));
        }
        sp.getInventory().setItem(slot, ItemStack.EMPTY);
        Manipulator.sync(sp);
    }

    /** 72000 ticks — never auto-fires; player always releases manually. */
    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 72000;
    }

    /** Hold modes work every tick while RMB is down. No speed penalty while charging. */
    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int remaining) {
        if (level.isClientSide() || !(entity instanceof ServerPlayer sp)) return;
        OrbMode mode = OrbMode.get(stack);
        int held = getUseDuration(stack, entity) - remaining;
        var movement = sp.getPersistentData();
        double moved =
                sp.position()
                        .distanceToSqr(
                                new net.minecraft.world.phys.Vec3(
                                        movement.getDoubleOr("mm_charge_x", 0.0),
                                        movement.getDoubleOr("mm_charge_y", 0.0),
                                        movement.getDoubleOr("mm_charge_z", 0.0)));
        movement.putInt(
                "mm_charge_still",
                held > 1 && moved < .0004 ? movement.getIntOr("mm_charge_still", 0) + 1 : 0);
        movement.putDouble("mm_charge_x", sp.getX());
        movement.putDouble("mm_charge_y", sp.getY());
        movement.putDouble("mm_charge_z", sp.getZ());
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
        if (level.isClientSide()) return;
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

    /** Fires one charged shot (or a Split volley) of {@code mode}'s projectile. */
    public static void fireVolley(
            Level level, Player player, ItemStack stack, int charged, OrbMode mode) {
        fireVolley(level, player, stack, charged, mode, false);
    }

    /** As above; {@code left}: fired with left click (Detonator bursts it with left click). */
    public static void fireVolley(
            Level level, Player player, ItemStack stack, int charged, OrbMode mode, boolean left) {
        if (mode == OrbMode.MASS) return; // Persistent Mass Driver handles its own release.
        // Pollen Drift: needs one charge stage, and then always drifts as a full cloud
        if (mode == OrbMode.GROWTH) {
            if (charged < 1) return;
            charged = 2;
        }
        // Short on tank: the shot drops to the strongest charge you can still pay for
        int affordable = charged;
        while (affordable > 0
                && mode.usesTank()
                && mode.tankLevel(stack) < mode.shotCost(stack, affordable)) affordable--;
        final int phase = affordable;
        int cost = mode.shotCost(stack, phase);
        if (mode.usesTank() && mode.tankLevel(stack) < cost) {
            if (player instanceof ServerPlayer sp) com.ethermod.beam.DryFire.signal(sp);
            return;
        }
        int shots = Traits.has(stack, "kinetic.split") ? phase + 1 : 1;
        if (mode.usesTank()) {
            mode.setTankLevel(stack, mode.tankLevel(stack) - cost);
            if (player instanceof ServerPlayer sp) Manipulator.sync(sp);
        }
        final ItemStack launchTraits = stack.copy();
        if (player.isAlive() && player.level() == level) {
            for (int n = 0; n < shots; n++) {
                var orb = spawnOrb(level, player, launchTraits, phase, mode);
                orb.setFiredLeft(left);
                orb.scaleDamage(1f / (float) shots);
                if (shots > 1) orb.turn((n - (shots - 1) / 2f) * 10f);
                if (n == 0
                        && mode == OrbMode.WATER
                        && phase == 2
                        && Traits.has(launchTraits, "hydraulic.tidecaller"))
                    orb.carryOwner();
            }
            com.ethermod.beam.ShotSounds.launch(level, player, mode, phase);
        }
        if (mode == OrbMode.GUST) {
            if (player.onGround() || !player.getPersistentData().getBooleanOr("mm_gust_boosted", false)) {
                var recoil = player.getLookAngle().scale(-(0.3 + 0.3 * phase));
                player.setDeltaMovement(player.getDeltaMovement().add(recoil));
                player.needsSync = true;
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
        return (baseSpeed + skillBonus) * com.ethermod.config.ServerConfig.miningSpeed();
    }

    /** Harvest level follows the stage: Stone can't take diamond ore, Iron can't take obsidian. */
    @Override
    public boolean isCorrectToolForDrops(
            ItemStack stack, net.minecraft.world.level.block.state.BlockState state) {
        return ManipulatorStage.get(stack).canHarvest(state) || com.ethermod.config.ServerConfig.alwaysHarvest(state);
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
