package com.ethermod.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The player's colours for the Manipulator, chosen on the K screen.
 *
 * <p>The mining beam (left click) and its block glow are always Plasmic orange. Orbs take their
 * mode's colour, the Energy orb Kinetic's. The crystal shows the mode colour; in the default Beam
 * mode (Beam and Energy, the two default Modules blended into one) it is Plasmic orange while
 * mining, Kinetic while an orb charges, and at rest keeps the colour of the last of the two used
 * (orange until then).
 *
 * <p>Body colour: set with a dye; tints the yellow trim of the model. Both are stored in the
 * stack's CustomData.
 */
public final class ManipulatorColors {

    private ManipulatorColors() {}

    public static final int DEFAULT_BEAM = 0xA66BFF;

    /** Plasmic Module colour: the beam. */
    public static final int PLASMIC = 0xE99F20;
    private static final String BEAM = "mm_beam_color";
    private static final String BODY = "mm_body_color";

    /** The orb colour: the active orb mode's colour (Energy uses Kinetic's). */
    public static int beam(ItemStack stack) {
        return OrbMode.get(stack).color;
    }

    /**
     * The model's crystal: the mode colour, except in the default Energy mode, where it is the
     * beam's Plasmic orange until an orb is charging.
     */
    public static int crystal(ItemStack stack, boolean charging) {
        OrbMode mode = OrbMode.get(stack);
        if (mode != OrbMode.ENERGY) return mode.color;
        return charging || lastOrb(stack) ? mode.color : PLASMIC;
    }

    private static final String LAST_ORB = "mm_last_orb";

    /** Beam mode: whether the last thing used was the Energy orb (else the mining beam). */
    public static boolean lastOrb(ItemStack stack) {
        return tag(stack).getBoolean(LAST_ORB);
    }

    /** Remembers whether the player last mined or charged an orb; syncs only on a change. */
    public static void setLastOrb(net.minecraft.server.level.ServerPlayer p, boolean orb) {
        ItemStack st = com.ethermod.manipulator.Manipulator.stack(p);
        if (lastOrb(st) == orb) return;
        CustomData.update(DataComponents.CUSTOM_DATA, st, t -> t.putBoolean(LAST_ORB, orb));
        com.ethermod.manipulator.Manipulator.sync(p);
    }

    /** The body colour (see {@link BodyPalette}), or -1 for the painted copper. */
    public static int body(ItemStack stack) {
        CompoundTag t = tag(stack);
        return t.contains(BODY) ? t.getInt(BODY) : -1;
    }

    /**
     * The beam's colour as RGBA floats: Plasmic orange for mining; the Light beacon and the
     * shows its own mode colour.
     */
    public static float[] beamRgba(ItemStack stack) {
        OrbMode mode = OrbMode.get(stack);
        int c = mode == OrbMode.LIGHT ? mode.color : PLASMIC;
        return new float[] {
            ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f, 0.9f
        };
    }

    /** True for a gem (beam) or a dye (trim), the two things the screen accepts. */
    public static boolean isBeamSource(ItemStack s) {
        return GemColors.colorOf(s) >= 0;
    }

    public static boolean isBodySource(ItemStack s) {
        return s.getItem() instanceof DyeItem;
    }

    /** The colour a gem gives the beam. */
    public static int beamFrom(ItemStack gem) {
        int c = GemColors.colorOf(gem);
        return c < 0 ? DEFAULT_BEAM : c;
    }

    public static int bodyFrom(ItemStack dye) {
        return ((DyeItem) dye.getItem()).getDyeColor().getTextureDiffuseColor() & 0xFFFFFF;
    }

    // ---- Palette: colours unlocked for good ------------------------------------

    private static final String BEAM_PALETTE = "mm_beam_palette";
    private static final String BODY_PALETTE = "mm_body_palette";
    public static final int MAX_PALETTE = 32;

    /** Unlocked colours, defaults first (amethyst beam; original yellow trim as -1). */
    public static int[] palette(ItemStack stack, boolean body) {
        int[] saved = tag(stack).getIntArray(body ? BODY_PALETTE : BEAM_PALETTE);
        int[] out = new int[saved.length + 1];
        out[0] = body ? -1 : DEFAULT_BEAM;
        System.arraycopy(saved, 0, out, 1, saved.length);
        return out;
    }

    /** Arbitrary trim RGB is available only after all sixteen vanilla dyes. */
    public static boolean customUnlocked(ItemStack stack) {
        for (net.minecraft.world.item.DyeColor dye : net.minecraft.world.item.DyeColor.values())
            if (!isUnlocked(stack, true, (dye.getTextureDiffuseColor() & 0xFFFFFF))) return false;
        return true;
    }

    public static boolean isUnlocked(ItemStack stack, boolean body, int rgb) {
        for (int c : palette(stack, body))
            if (rgb == -1 ? c == -1 : c != -1 && (c & 0xFFFFFF) == (rgb & 0xFFFFFF)) return true;
        return false;
    }

    /** Adds a colour to the palette; false if it was already there or the palette is full. */
    public static boolean unlock(ItemStack stack, boolean body, int rgb) {
        if (rgb != -1) rgb &= 0xFFFFFF;
        if (isUnlocked(stack, body, rgb)) return false;
        int[] saved = tag(stack).getIntArray(body ? BODY_PALETTE : BEAM_PALETTE);
        if (saved.length >= MAX_PALETTE) return false;
        int[] next = java.util.Arrays.copyOf(saved, saved.length + 1);
        next[saved.length] = rgb;
        CustomData.update(
                DataComponents.CUSTOM_DATA,
                stack,
                t -> t.putIntArray(body ? BODY_PALETTE : BEAM_PALETTE, next));
        return true;
    }

    public static void setBeam(ItemStack stack, int rgb) {
        put(stack, BEAM, rgb);
    }

    public static void setBody(ItemStack stack, int rgb) {
        if (rgb < 0) CustomData.update(DataComponents.CUSTOM_DATA, stack, t -> t.remove(BODY));
        else put(stack, BODY, rgb);
    }

    private static void put(ItemStack stack, String key, int v) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, t -> t.putInt(key, v));
    }

    private static CompoundTag tag(ItemStack stack) {
        CustomData d = stack.get(DataComponents.CUSTOM_DATA);
        return d == null ? new CompoundTag() : d.getUnsafe();
    }
}
