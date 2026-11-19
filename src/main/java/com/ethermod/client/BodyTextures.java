package com.ethermod.client;

import com.ethermod.EtherMod;
import com.ethermod.item.BodyPalette;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The model texture recoloured for a body colour: every pixel of the painted red ramp is swapped
 * for the matching tone of {@link BodyPalette#ramp}. One texture per colour, built on first use;
 * the few most recent are kept (the custom colour picker can go through many).
 */
@OnlyIn(Dist.CLIENT)
public final class BodyTextures {

    private BodyTextures() {}

    public static final ResourceLocation BASE =
            ResourceLocation.fromNamespaceAndPath(
                    EtherMod.MODID, "textures/item/ether_manipulator.png");

    private static final int KEEP = 24;

    private static final Map<Integer, ResourceLocation> CACHE =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, ResourceLocation> e) {
                    if (size() <= KEEP) return false;
                    Minecraft.getInstance().getTextureManager().release(e.getValue());
                    return true;
                }
            };

    /** The texture for a body colour; the painted one for -1 (default) or if anything fails. */
    public static ResourceLocation get(int body) {
        if (body < 0) return BASE;
        // cache hit first: the ramp and name are only needed to build a new texture
        ResourceLocation have = CACHE.get(body);
        if (have != null) return have;
        return build(body, BodyPalette.ramp(body), String.format("%06x", body));
    }

    /**
     * The texture for a stack: its body colour, or for Default the tier's material (copper,
     * iron, diamond, netherite).
     */
    public static ResourceLocation get(int body, com.ethermod.item.ManipulatorStage stage) {
        if (body >= 0) return get(body);
        int[] material = BodyPalette.material(stage);
        if (material == null) return BASE;
        // keys below -1 never collide with a colour (0..0xFFFFFF) or the Default (-1)
        return build(-2 - stage.ordinal(), material, "material_" + stage.ordinal());
    }

    private static ResourceLocation build(int key, int[] tones, String name) {
        ResourceLocation have = CACHE.get(key);
        if (have != null) return have;
        ResourceLocation loc =
                ResourceLocation.fromNamespaceAndPath(
                        EtherMod.MODID, "dynamic/manipulator_body_" + name);
        try (InputStream in =
                Minecraft.getInstance().getResourceManager().getResourceOrThrow(BASE).open()) {
            NativeImage img = NativeImage.read(in);
            recolour(img, tones);
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(img));
        } catch (Exception e) {
            EtherMod.LOGGER.warn("Could not recolour the Manipulator texture", e);
            loc = BASE;
        }
        CACHE.put(key, loc);
        return loc;
    }

    private static void recolour(NativeImage img, int[] tones) {
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int abgr = img.getPixelRGBA(x, y); // NativeImage stores ABGR
                int rgb = (abgr & 0xFF) << 16 | (abgr & 0xFF00) | (abgr >> 16 & 0xFF);
                for (int i = 0; i < 5; i++) {
                    if (rgb != BodyPalette.PAINTED[i]) continue;
                    int t = tones[i];
                    img.setPixelRGBA(
                            x,
                            y,
                            (abgr & 0xFF000000)
                                    | (t & 0xFF) << 16
                                    | (t & 0xFF00)
                                    | (t >> 16 & 0xFF));
                    break;
                }
            }
        }
    }
}
