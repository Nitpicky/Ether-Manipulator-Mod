package com.ethermod.sound;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

/**
 * The vanilla sounds the mod plays, under its own names (sounds.json points each one at the vanilla
 * event), so the client volume settings can tell them apart: shot/ = shots and charging, ui/ = cues
 * and screens, world/ = everything else. Not registered: they travel as direct holders.
 */
public final class ModSounds {

    private ModSounds() {}

    private static SoundEvent a(String path) {
        return SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath("ethermod", path));
    }

    public static final SoundEvent SHOT_AMETHYST_BLOCK_CHIME = a("shot/block.amethyst_block.chime");
    public static final SoundEvent SHOT_BEACON_POWER_SELECT = a("shot/block.beacon.power_select");
    public static final SoundEvent SHOT_BREEZE_SHOOT = a("shot/entity.breeze.shoot");
    public static final SoundEvent SHOT_BUCKET_FILL = a("shot/item.bucket.fill");
    public static final SoundEvent SHOT_BUCKET_FILL_LAVA = a("shot/item.bucket.fill_lava");
    public static final SoundEvent SHOT_COPPER_BULB_TURN_ON = a("shot/block.copper_bulb.turn_on");
    public static final SoundEvent SHOT_FIRE_EXTINGUISH = a("shot/block.fire.extinguish");
    public static final SoundEvent SHOT_FLINTANDSTEEL_USE = a("shot/item.flintandsteel.use");
    public static final SoundEvent SHOT_GLASS_BREAK = a("shot/block.glass.break");
    public static final SoundEvent SHOT_GRASS_HIT = a("shot/block.grass.hit");
    public static final SoundEvent SHOT_POWDER_SNOW_BREAK = a("shot/block.powder_snow.break");
    public static final SoundEvent UI_DRY_BEEP = a("ui/dry_beep");
    public static final SoundEvent UI_AMETHYST_BLOCK_PLACE = a("ui/block.amethyst_block.place");
    public static final SoundEvent UI_BEACON_POWER_SELECT = a("ui/block.beacon.power_select");
    public static final SoundEvent UI_COPPER_BULB_TURN_OFF = a("ui/block.copper_bulb.turn_off");
    public static final SoundEvent UI_GENERIC_SPLASH = a("ui/entity.generic.splash");
    public static final SoundEvent UI_PLAYER_SPLASH = a("ui/entity.player.splash");
    public static final SoundEvent UI_RESPAWN_ANCHOR_CHARGE = a("ui/block.respawn_anchor.charge");
    public static final SoundEvent WORLD_AMETHYST_BLOCK_CHIME = a("world/block.amethyst_block.chime");
    public static final SoundEvent WORLD_AMETHYST_BLOCK_HIT = a("world/block.amethyst_block.hit");
    public static final SoundEvent WORLD_AMETHYST_BLOCK_STEP = a("world/block.amethyst_block.step");
    public static final SoundEvent WORLD_AMETHYST_CLUSTER_BREAK = a("world/block.amethyst_cluster.break");
    public static final SoundEvent WORLD_AXE_SCRAPE = a("world/item.axe.scrape");
    public static final SoundEvent WORLD_AXE_STRIP = a("world/item.axe.strip");
    public static final SoundEvent WORLD_AXE_WAX_OFF = a("world/item.axe.wax_off");
    public static final SoundEvent WORLD_BEACON_ACTIVATE = a("world/block.beacon.activate");
    public static final SoundEvent WORLD_BEEHIVE_SHEAR = a("world/block.beehive.shear");
    public static final SoundEvent WORLD_BLAZE_AMBIENT = a("world/entity.blaze.ambient");
    public static final SoundEvent WORLD_BLAZE_DEATH = a("world/entity.blaze.death");
    public static final SoundEvent WORLD_BLAZE_HURT = a("world/entity.blaze.hurt");
    public static final SoundEvent WORLD_BLAZE_SHOOT = a("world/entity.blaze.shoot");
    public static final SoundEvent WORLD_BONE_MEAL_USE = a("world/item.bone_meal.use");
    public static final SoundEvent WORLD_BREEZE_IDLE_AIR = a("world/entity.breeze.idle_air");
    public static final SoundEvent WORLD_BREEZE_IDLE_GROUND = a("world/entity.breeze.idle_ground");
    public static final SoundEvent WORLD_BREEZE_INHALE = a("world/entity.breeze.inhale");
    public static final SoundEvent WORLD_BREEZE_JUMP = a("world/entity.breeze.jump");
    public static final SoundEvent WORLD_BREEZE_SHOOT = a("world/entity.breeze.shoot");
    public static final SoundEvent WORLD_BUCKET_EMPTY = a("world/item.bucket.empty");
    public static final SoundEvent WORLD_BUCKET_EMPTY_LAVA = a("world/item.bucket.empty_lava");
    public static final SoundEvent WORLD_BUCKET_FILL = a("world/item.bucket.fill");
    public static final SoundEvent WORLD_BUCKET_FILL_LAVA = a("world/item.bucket.fill_lava");
    public static final SoundEvent WORLD_CHAIN_HIT = a("world/block.chain.hit");
    public static final SoundEvent WORLD_CHAIN_PLACE = a("world/block.chain.place");
    public static final SoundEvent WORLD_CROP_PLANTED = a("world/item.crop.plant");
    public static final SoundEvent WORLD_CROSSBOW_SHOOT = a("world/item.crossbow.shoot");
    public static final SoundEvent WORLD_DEEPSLATE_BREAK = a("world/block.deepslate.break");
    public static final SoundEvent WORLD_DISPENSER_FAIL = a("world/block.dispenser.fail");
    public static final SoundEvent WORLD_ENDERMAN_TELEPORT = a("world/entity.enderman.teleport");
    public static final SoundEvent WORLD_FIRECHARGE_USE = a("world/item.firecharge.use");
    public static final SoundEvent WORLD_FIRE_EXTINGUISH = a("world/block.fire.extinguish");
    public static final SoundEvent WORLD_FISHING_BOBBER_RETRIEVE = a("world/entity.fishing_bobber.retrieve");
    public static final SoundEvent WORLD_FLINTANDSTEEL_USE = a("world/item.flintandsteel.use");
    public static final SoundEvent WORLD_GENERIC_EAT = a("world/entity.generic.eat");
    public static final SoundEvent WORLD_GENERIC_EXPLODE = a("world/entity.generic.explode");
    public static final SoundEvent WORLD_GLASS_BREAK = a("world/block.glass.break");
    public static final SoundEvent WORLD_GRAVEL_BREAK = a("world/block.gravel.break");
    public static final SoundEvent WORLD_HEAVY_CORE_PLACE = a("world/block.heavy_core.place");
    public static final SoundEvent WORLD_HOE_TILL = a("world/item.hoe.till");
    public static final SoundEvent WORLD_ITEM_PICKUP = a("world/entity.item.pickup");
    public static final SoundEvent WORLD_LAVA_EXTINGUISH = a("world/block.lava.extinguish");
    public static final SoundEvent WORLD_MACE_SMASH_GROUND = a("world/item.mace.smash_ground");
    public static final SoundEvent WORLD_MACE_SMASH_GROUND_HEAVY = a("world/item.mace.smash_ground_heavy");
    public static final SoundEvent WORLD_NOTE_BLOCK_CHIME = a("world/block.note_block.chime");
    public static final SoundEvent WORLD_NOTE_BLOCK_HAT = a("world/block.note_block.hat");
    public static final SoundEvent WORLD_PISTON_CONTRACT = a("world/block.piston.contract");
    public static final SoundEvent WORLD_PLAYER_ATTACK_SWEEP = a("world/entity.player.attack.sweep");
    public static final SoundEvent WORLD_PLAYER_SPLASH = a("world/entity.player.splash");
    public static final SoundEvent WORLD_PLAYER_SPLASH_HIGH_SPEED = a("world/entity.player.splash.high_speed");
    public static final SoundEvent WORLD_PUFFER_FISH_BLOW_OUT = a("world/entity.puffer_fish.blow_out");
    public static final SoundEvent WORLD_SHEEP_SHEAR = a("world/entity.sheep.shear");
    public static final SoundEvent WORLD_SHOVEL_FLATTEN = a("world/item.shovel.flatten");
    public static final SoundEvent WORLD_SLIME_ATTACK = a("world/entity.slime.attack");
    public static final SoundEvent WORLD_TOTEM_USE = a("world/item.totem.use");
    public static final SoundEvent WORLD_WIND_CHARGE_BURST = a("world/entity.wind_charge.wind_burst");
    // Stasis (Pneumatic): a ding that speeds up, a low ring on release
    public static final SoundEvent WORLD_STASIS_DING = a("world/stasis.ding");
}
