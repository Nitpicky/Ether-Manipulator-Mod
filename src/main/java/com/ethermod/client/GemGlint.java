package com.ethermod.client;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * The Enchanted Gemstones' glint: vanilla's item glint (same scroll, same blend), drawn from a copy
 * of the game's glint texture tinted to the gem's Module colour. 26.3 draws an item and its glint
 * in one pass, so the gem's quads swap to a render type that samples the tinted copy
 * (GemGlintMixin). The copies are made from the current glint (a resource pack's too) when first
 * needed after each resource reload; nothing is shipped.
 */
public final class GemGlint {
    private GemGlint() {}

    /** Gem id -> Module colour (ui/screen.json). */
    private static final Map<String, Integer> COLOURS =
            Map.of(
                    "sirenite", 0x2074E9,
                    "gehennite", 0xE95C20,
                    "stratite", 0xD48050,
                    "zephyrite", 0x20E9C1,
                    "quetzalite", 0x31E920,
                    "atenite", 0xE9C820,
                    "oculite", 0x9520E9);

    /** Gem + atlas -> render type (null when the tinted glint could not be made). */
    private static final Map<String, RenderType> TYPES = new HashMap<>();

    private static final Map<String, Identifier> TEXTURES = new HashMap<>();

    /** Forgets the tinted copies (resource reload). */
    public static void clear() {
        TEXTURES.clear();
        TYPES.clear();
    }

    /** The gem whose item texture this sprite is, or null. */
    private static String gemOf(TextureAtlasSprite sprite) {
        Identifier name = sprite.contents().name();
        if (!"ethermod".equals(name.getNamespace()) || !name.getPath().startsWith("item/")) return null;
        String gem = name.getPath().substring(5);
        return COLOURS.containsKey(gem) ? gem : null;
    }

    /** The tinted glint render type for a gem's quad, or {@code vanilla} for anything else. */
    public static RenderType glintFor(TextureAtlasSprite sprite, RenderType vanilla) {
        String gem = gemOf(sprite);
        if (gem == null) return vanilla;
        Identifier atlas = sprite.atlasLocation();
        RenderType type =
                TYPES.computeIfAbsent(
                        gem + "@" + atlas,
                        k -> {
                            Identifier tex = texture(gem);
                            if (tex == null) return null;
                            return RenderType.create(
                                    "ethermod_gem_glint_" + gem,
                                    RenderSetup.builder(RenderPipelines.ITEM_CUTOUT_GLINT)
                                            .withTexture("Sampler0", atlas)
                                            .withTexture("GlintSampler", tex)
                                            .setTextureTransform(TextureTransform.GLINT_TEXTURING)
                                            .useLightmap()
                                            .useOverlay()
                                            .affectsCrumbling()
                                            .setOutline(RenderSetup.OutlineProperty.AFFECTS_OUTLINE)
                                            .createRenderSetup());
                        });
        return type == null ? vanilla : type;
    }

    private static float lift(int c, int shift) {
        return Math.min(1f, ((c >> shift) & 255) / 255f * 1.25f + .08f);
    }

    /** The vanilla glint made grey, then tinted to the gem's colour. */
    private static Identifier texture(String gem) {
        Identifier have = TEXTURES.get(gem);
        if (have != null) return have;
        int c = COLOURS.get(gem);
        float r = lift(c, 16), g = lift(c, 8), b = lift(c, 0);
        Identifier loc = Identifier.fromNamespaceAndPath("ethermod", "dynamic/gem_glint_" + gem);
        try (var in =
                Minecraft.getInstance()
                        .getResourceManager()
                        .getResourceOrThrow(ItemFeatureRenderer.ENCHANTED_GLINT_ITEM)
                        .open()) {
            NativeImage img = NativeImage.read(in);
            int w = img.getWidth(), h = img.getHeight(), max = 1;
            int[] lum = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int p = img.getPixel(x, y);
                    int l = ((p & 255) + ((p >> 8) & 255) + ((p >> 16) & 255)) / 3;
                    lum[y * w + x] = l;
                    max = Math.max(max, l);
                }
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int l = Math.min(255, lum[y * w + x] * 255 / max);
                    int rr = Math.round(l * r), gg = Math.round(l * g), bb = Math.round(l * b);
                    img.setPixel(x, y, 0xFF000000 | rr << 16 | gg << 8 | bb);
                }
            Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(loc::toString, img));
        } catch (Exception e) {
            com.ethermod.EtherMod.LOGGER.warn("Could not build the gem glint", e);
            return null;
        }
        TEXTURES.put(gem, loc);
        return loc;
    }
}
