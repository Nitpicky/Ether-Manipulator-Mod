package com.ethermod.trait;

import com.ethermod.EtherMod;
import com.ethermod.manipulator.Manipulator;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Player effects of the modules (Gravitic, Photonic, Pneumatic, Hydraulic, Pyrotechnic). */
public final class MobilityEffects {

    private MobilityEffects() {}

    private static final String SOFT_LANDING = "ethermod_soft_landing";
    private static final Identifier STRIDE_ID =
            Identifier.fromNamespaceAndPath(EtherMod.MODID, "stride");
    private static final Identifier TAILWIND_ID =
            Identifier.fromNamespaceAndPath(EtherMod.MODID, "tailwind");

    /** Soft Landing: no fall damage for a few seconds after a tether pull or Tidecaller launch. */
    public static void markSoftLanding(Player player) {
        player.getPersistentData().putLong(SOFT_LANDING, player.level().getGameTime());
    }

    /**
     * True while a Module legitimately holds the player in the air: shifted gravity, Airwalk, a
     * zipline ride, or a tether/reel/launch that marked a soft landing in the last 10 ticks (the
     * taut rope marks every tick). Clears vanilla's "Flying is not enabled" hover counter.
     */
    public static boolean legitimatelyAirborne(ServerPlayer sp) {
        if (MovementPhysics.down(sp) != net.minecraft.core.Direction.DOWN) return true;
        if (Traits.has(Manipulator.stack(sp), "gravitic.airwalk")) return true;
        if (Ziplines.isRiding(sp)) return true;
        var d = sp.getPersistentData();
        return d.contains(SOFT_LANDING)
                && sp.level().getGameTime() - d.getLongOr(SOFT_LANDING, 0L) < 10;
    }

    private static final String NO_FALL = "ethermod_no_fall";
    private static final String AIR_JUMP = "ethermod_air_jump";

    /**
     * Gale Step (Pneumatic common 1): the client jumped in mid-air; keep the fall harmless and show
     * it.
     */
    public static void airJump(ServerPlayer sp) {
        if (!Traits.hasLine(Manipulator.stack(sp), Traits.PNEUMATIC)) return;
        // the client says it jumped in mid-air: only believe it while the player really is in
        // the air (not standing, swimming, climbing, flying or riding). No block-support test:
        // the client allows a jump a hair above the ground (not yet onGround), and refusing it
        // there would leave that landing's fall damage in place
        if (sp.onGround()
                || sp.isInWater()
                || sp.isInLava()
                || sp.onClimbable()
                || sp.isPassenger()
                || sp.isFallFlying()
                || sp.getAbilities().flying
                || sp.isSpectator()) return;
        long now = sp.level().getGameTime();
        if (sp.getPersistentData().getBooleanOr("mm_airjump_used", false)) return;
        if (now - sp.getPersistentData().getLongOr(AIR_JUMP, 0L) < 8) return;
        sp.getPersistentData().putBoolean("mm_airjump_used", true);
        sp.getPersistentData().putLong(AIR_JUMP, now);
        sp.resetFallDistance();
        ServerLevel sl = sp.level();
        sl.sendParticles(ParticleTypes.GUST, sp.getX(), sp.getY(), sp.getZ(), 1, 0, 0, 0, 0);
        sl.sendParticles(
                ParticleTypes.CLOUD, sp.getX(), sp.getY(), sp.getZ(), 8, 0.3, 0.05, 0.3, 0.02);
        sl.playSound(
                null,
                sp.getX(),
                sp.getY(),
                sp.getZ(),
                com.ethermod.sound.ModSounds.WORLD_BREEZE_JUMP,
                SoundSource.PLAYERS,
                0.6f,
                1.4f);
    }

    /**
     * No fall damage at all for the next landing (within 10 s): Updraft, Slingshot III, Gale Step.
     */
    public static void markNoFall(Player player) {
        player.getPersistentData().putLong(NO_FALL, player.level().getGameTime());
        player.resetFallDistance();
    }

    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        if (!(event.getEntity() instanceof Player p)) return;
        if (Traits.hasCommon(Manipulator.stack(p), Traits.GRAVITIC, 0)
                || Traits.hasCommon(Manipulator.stack(p), Traits.PNEUMATIC, 0)) {
            event.setDamageMultiplier(0);
            return;
        }
        long nf = p.getPersistentData().getLongOr(NO_FALL, 0L);
        if (nf != 0 && p.level().getGameTime() - nf <= 200) {
            event.setDamageMultiplier(0);
            p.getPersistentData().remove(NO_FALL);
            return;
        }
        long t = p.getPersistentData().getLongOr(SOFT_LANDING, 0L);
        if (t == 0 || p.level().getGameTime() - t > 100) return;
        if (Traits.hasCommon(Manipulator.stack(p), Traits.GRAVITIC, 0)) {
            event.setDamageMultiplier(0);
            p.getPersistentData().remove(SOFT_LANDING);
        }
    }

    /**
     * While summoned: Stride (+10% speed, the Mobility badge), Night Optics (Mobility common),
     * Gills (Hydraulics common), Fire Ward III (fire resistance).
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer sp) || sp.tickCount % 10 != 0) return;
        boolean summoned = Manipulator.isSummoned(sp);
        ItemStack stack = Manipulator.stack(sp);

        AttributeInstance speed = sp.getAttribute(Attributes.MOVEMENT_SPEED);
        boolean stride = Traits.hasLine(stack, Traits.GRAVITIC);
        if (speed != null) {
            if (stride && !speed.hasModifier(STRIDE_ID)) {
                speed.addTransientModifier(
                        new AttributeModifier(
                                STRIDE_ID, 0.10, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            } else if (!stride && speed.hasModifier(STRIDE_ID)) {
                speed.removeModifier(STRIDE_ID);
            }
        }
        // Tailwind (Pneumatic common 2): sprinting is 15% faster, with a wind trail
        boolean tail = false;
        if (speed != null) {
            if (tail && !speed.hasModifier(TAILWIND_ID)) {
                speed.addTransientModifier(
                        new AttributeModifier(
                                TAILWIND_ID,
                                0.15,
                                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            } else if (!tail && speed.hasModifier(TAILWIND_ID)) {
                speed.removeModifier(TAILWIND_ID);
            }
        }
        if (tail)
            sp.level()
                    .sendParticles(
                            ParticleTypes.CLOUD,
                            sp.getX(),
                            sp.getY() + 0.2,
                            sp.getZ(),
                            2,
                            0.2,
                            0.05,
                            0.2,
                            0.01);

        if (Traits.hasCommon(stack, Traits.PHOTONIC, 1)) refresh(sp, MobEffects.NIGHT_VISION);
        if (Traits.hasCommon(stack, Traits.HYDRAULIC, 0)) refresh(sp, MobEffects.WATER_BREATHING);
        if (Traits.hasLine(stack, Traits.PYROTECHNIC)) refresh(sp, MobEffects.FIRE_RESISTANCE);
    }

    /**
     * Fire Ward: your own fire and lava never hurt you; other fire hurts half as much (a quarter at
     * II).
     */
    @SubscribeEvent
    public static void onHurt(
            net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!event.getSource().is(net.minecraft.tags.DamageTypeTags.IS_FIRE)) return;
        if (Traits.hasLine(Manipulator.stack(sp), Traits.PYROTECHNIC)) {
            event.setCanceled(true);
            sp.clearFire();
        }
    }

    private static final String PHOENIX = "ethermod_phoenix";

    /** Phoenix: once every 10 minutes, dying while on fire leaves you at 4 hearts instead. */
    @SubscribeEvent
    public static void onDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!Traits.has(Manipulator.stack(sp), "pyrotechnic.phoenix")) return;
        long now = sp.level().getGameTime();
        long last = sp.getPersistentData().getLongOr(PHOENIX, 0L);
        if (last != 0 && now - last < 12000) return;
        event.setCanceled(true);
        sp.setHealth(8f);
        sp.clearFire();
        sp.getPersistentData().putLong(PHOENIX, now);
        ServerLevel sl = sp.level();
        OwnedFire.fireRing(sl, sp.blockPosition(), sp, 2);
        for (net.minecraft.world.entity.LivingEntity e :
                sl.getEntitiesOfClass(
                        net.minecraft.world.entity.LivingEntity.class,
                        sp.getBoundingBox().inflate(4),
                        e -> !com.ethermod.beam.Mounts.own(sp, e))) {
            e.igniteForSeconds(6);
        }
        sl.sendParticles(
                ParticleTypes.FLAME, sp.getX(), sp.getY() + 1, sp.getZ(), 60, 1.2, 1, 1.2, 0.08);
        sl.sendParticles(
                ParticleTypes.TOTEM_OF_UNDYING,
                sp.getX(),
                sp.getY() + 1,
                sp.getZ(),
                30,
                0.6,
                0.8,
                0.6,
                0.3);
        sl.playSound(
                null, sp.blockPosition(), com.ethermod.sound.ModSounds.WORLD_TOTEM_USE, SoundSource.PLAYERS, 0.8f, 1.3f);
        sp.sendOverlayMessage(
                Component.translatable("message.ethermod.phoenix").withStyle(ChatFormatting.GOLD));
    }

    /** Keeps an ambient, icon-only effect topped up while a trait holds it. */
    private static void refresh(
            ServerPlayer sp,
            net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        MobEffectInstance cur = sp.getEffect(effect);
        if (cur == null || cur.getDuration() < 240) {
            sp.addEffect(new MobEffectInstance(effect, 300, 0, true, false, true));
        }
    }
}
