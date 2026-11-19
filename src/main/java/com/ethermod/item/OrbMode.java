package com.ethermod.item;

import com.ethermod.trait.Traits;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

/**
 * The Manipulator's modes (chosen with the mode wheel) and the projectiles they fire.
 *
 * <p>Each wheel mode is one tool with its own left and right click (see {@code LeftClick} for the
 * left clicks). Beam (ENERGY) is always there: LMB mines, RMB charges the Energy orb. The others
 * come with their Module: Hydraulic (WATER: LMB water blob, RMB ice shard), Pyrotechnic (LAVA: LMB
 * molten glob, RMB fire seed), Botanic (Growth), Photonic (Light), Gravitic (Tether), Pneumatic
 * (Gust), Tectonic (Mass Driver).
 *
 * <p>FROST and IGNITE are no longer wheel modes, only the right-click projectiles of the
 * Hydraulic and Pyrotechnic tools. VACUUM is gone.
 */
public enum OrbMode {
    ENERGY(0x20C1E9, Items.AMETHYST_SHARD),
    // LEFTOVER: Vacuum is retired and never unlocked. The constant stays because saved modes are
    // stored by ordinal: removing it would shift every later mode. Do not reuse or remove it.
    VACUUM(0xB070FF, Items.HOPPER),
    WATER(0x3F8CFF, Items.WATER_BUCKET),
    LAVA(0xFF7020, Items.LAVA_BUCKET),
    FROST(0xC8F0FF, Items.SNOWBALL),
    IGNITE(0xFFB040, Items.FLINT_AND_STEEL),
    GROWTH(0x70E060, Items.BONE_MEAL),
    LIGHT(0xFFF4B0, Items.GLOWSTONE_DUST),
    TETHER(0xD060FF, Items.LEAD),
    GUST(0xB8E8E0, Items.FEATHER),
    MASS(0xE0A040, Items.COBBLESTONE);

    private static final String TAG_MODE = "mm_orb_mode";
    /** The one tank every projectile draws from (the old water/lava/fertilizer tanks merged). */
    private static final String TAG_TANK = "mm_tank";

    /** RGB tint for the orb and HUD. */
    public final int color;

    /** Icon shown in the mode wheel and HUD. */
    public final net.minecraft.world.item.Item icon;

    OrbMode(int color, net.minecraft.world.item.Item icon) {
        this.color = color;
        this.icon = icon;
    }

    /**
     * Translated name (lang key ethermod.orb_mode.<name>).
     */
    public net.minecraft.network.chat.MutableComponent title() {
        return net.minecraft.network.chat.Component.translatable(
                "ethermod.orb_mode." + name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Modes that work while RMB is held, with no charge-and-release. */
    public boolean isHold() {
        return this == TETHER;
    }

    /** The projectile this tool charges on right click (Hydraulic: ice shard; Pyrotechnic: seed). */
    public OrbMode rmbShot() {
        return switch (this) {
            case WATER -> FROST;
            case LAVA -> IGNITE;
            default -> this;
        };
    }

    /** The wheel mode a projectile belongs to (the ice shard is the Hydraulic tool's). */
    public OrbMode tool() {
        return switch (this) {
            case FROST -> WATER;
            case IGNITE -> LAVA;
            default -> this;
        };
    }

    /** Left clicks that charge like the right-click orbs (same behaviour, other button). */
    public boolean chargesOnLeft() {
        return this == WATER || this == LAVA;
    }

    /** Downward pull per tick on the projectile (0 = flies straight). */
    public float gravity() {
        return switch (this) {
            case WATER -> 0.045f;
            case LAVA -> 0.07f;
            case IGNITE, GROWTH -> 0.03f;
            default -> 0f;
        };
    }

    /** Launch speed per charge phase. */
    public float speed(int phase) {
        float[] s =
                switch (this) {
                    case WATER -> new float[] {0.8f, 1.05f, 1.3f};
                    case LAVA -> new float[] {0.8f, 1.1f, 1.4f};
                    case FROST -> new float[] {2.2f, 2.6f, 3.0f};
                    case IGNITE -> new float[] {1.2f, 1.6f, 2.0f};
                    case GROWTH -> new float[] {1.0f, 0.3f, 0.3f};
                    case LIGHT -> new float[] {0.9f, 1.1f, 1.3f};
                    case GUST -> new float[] {1.6f, 1.9f, 1.2f};
                    case MASS -> new float[] {1.2f, 1.6f, 2.2f};
                    case TETHER -> new float[] {2.4f, 2.4f, 2.4f};
                    default -> new float[] {1.0f, 1.8f, 2.8f};
                };
        return s[Math.max(0, Math.min(2, phase))];
    }

    public float r() {
        return ((color >> 16) & 0xFF) / 255f;
    }

    public float g() {
        return ((color >> 8) & 0xFF) / 255f;
    }

    public float b() {
        return (color & 0xFF) / 255f;
    }

    /** Whether this mode is available on the given Manipulator. */
    public boolean isUnlocked(ItemStack stack) {
        return switch (this) {
            case ENERGY -> true;
            case VACUUM -> false;
            // the ice shard and fire seed are the right clicks of Hydraulic and Pyrotechnic
            case FROST, IGNITE -> false;
            case WATER -> Traits.hasLine(stack, Traits.HYDRAULIC);
            case LAVA -> Traits.hasLine(stack, Traits.PYROTECHNIC);
            case GROWTH -> Traits.hasLine(stack, Traits.BOTANIC);
            case LIGHT -> Traits.hasLine(stack, Traits.PHOTONIC);
            case TETHER -> Traits.hasLine(stack, Traits.GRAVITIC);
            case GUST -> Traits.hasLine(stack, Traits.PNEUMATIC);
            case MASS -> Traits.hasLine(stack, Traits.TECTONIC);
        };
    }

    /**
     * Every projectile draws from the shared tank. Free: Tether (Gravitic), Mass Driver
     * (Tectonic); and, outside projectiles, mining and the Light Beacon.
     */
    public boolean usesTank() {
        return this != TETHER && this != MASS && this != VACUUM;
    }

    public static OrbMode byId(int id) {
        OrbMode[] v = values();
        return id >= 0 && id < v.length ? v[id] : ENERGY;
    }

    // ---- Stack data ---------------------------------------------------------

    private static CompoundTag tag(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).getUnsafe();
    }

    private static void edit(ItemStack stack, java.util.function.Consumer<CompoundTag> f) {
        CompoundTag t = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        f.accept(t);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(t));
    }

    /** The selected mode, or Energy if the selected one is no longer unlocked. */
    public static OrbMode get(ItemStack stack) {
        OrbMode m = byId(tag(stack).getInt(TAG_MODE)).tool();
        return m.isUnlocked(stack) ? m : ENERGY;
    }

    public static void set(ItemStack stack, OrbMode mode) {
        edit(stack, t -> t.putInt(TAG_MODE, mode.ordinal()));
    }

    /** Shared tank size: 6 units, +2 for each of Reservoir, Crucible, Seedbank and Bellows. */
    public static int capacity(ItemStack stack) {
        int n = 0;
        for (String t :
                new String[] {
                    "hydraulic.reservoir", "pyrotechnic.crucible", "botanic.seedbank", "pneumatic.bellows"
                }) if (Traits.has(stack, t)) n++;
        return com.ethermod.config.ServerConfig.tankBase() + com.ethermod.config.ServerConfig.tankPerTrait() * n;
    }

    /** Units in the shared tank. A Manipulator that never had one starts full. */
    public static int tank(ItemStack stack) {
        CompoundTag t = tag(stack);
        int cap = capacity(stack);
        return t.contains(TAG_TANK) ? Math.max(0, Math.min(cap, t.getInt(TAG_TANK))) : cap;
    }

    public static void setTank(ItemStack stack, int units) {
        int v = Math.max(0, Math.min(capacity(stack), units));
        edit(stack, t -> t.putInt(TAG_TANK, v));
    }

    /** Units one shot costs at this charge phase (0-2); 0 for the free modes. */
    public int shotCost(ItemStack s, int phase) {
        int p = Math.max(0, Math.min(2, phase));
        return switch (this) {
            case ENERGY, GUST, FROST, IGNITE -> 1;
            case LIGHT -> p == 2 ? 2 : 1;
            case WATER, LAVA, GROWTH -> 4;
            default -> 0;
        };
    }

    /** Left-click cost that is not a charged shot: Gust's Stasis Grenade. */
    public static final int GRENADE_COST = 2;

    /** The shared tank's level (kept per mode for callers that ask a mode). */
    public int tankLevel(ItemStack stack) {
        return tank(stack);
    }

    public int tankCapacity(ItemStack stack) {
        return capacity(stack);
    }

    public void setTankLevel(ItemStack stack, int v) {
        setTank(stack, v);
    }
}
