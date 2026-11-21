package com.ethermod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * The Enchanted Gemstones' glint: vanilla's item glint (same scroll, same additive blend), drawn
 * with a grey copy of the vanilla glint tinted to the gem's Module colour.
 */
public final class GemGlint extends RenderType {
    private GemGlint(String n, VertexFormat f, VertexFormat.Mode m, int s, boolean a, boolean b, Runnable u, Runnable c) {
        super(n, f, m, s, a, b, u, c);
    }

    /**
     * A grey copy of the vanilla item glint (or a resource pack's), made at runtime from the
     * game's own texture on every resource reload, then tinted per gem. Nothing is shipped.
     */
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath("ethermod", "dynamic/gem_glint");

    private static final Identifier VANILLA =
            Identifier.withDefaultNamespace("textures/misc/enchanted_glint_item.png");

    /** Rebuilds the grey glint from the current vanilla one (called on resource reload). */
    public static void rebuildTexture(net.minecraft.server.packs.resources.ResourceManager rm) {
        try (var in = rm.open(VANILLA)) {
            var img = com.mojang.blaze3d.platform.NativeImage.read(in);
            int w = img.getWidth(), h = img.getHeight(), max = 1;
            int[] lum = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int c = img.getPixel(x, y);
                    int l = ((c & 255) + ((c >> 8) & 255) + ((c >> 16) & 255)) / 3;
                    lum[y * w + x] = l;
                    max = Math.max(max, l);
                }
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    int l = Math.min(255, lum[y * w + x] * 255 / max);
                    img.setPixel(x, y, 0xFF000000 | l << 16 | l << 8 | l);
                }
            net.minecraft.client.Minecraft.getInstance()
                    .getTextureManager()
                    .register(TEXTURE, new net.minecraft.client.renderer.texture.DynamicTexture(() -> "ethermod:gem_glint", img));
        } catch (Exception e) {
            com.ethermod.EtherMod.LOGGER.warn("Could not build the gem glint", e);
        }
    }

    /** Gem id -> Module colour (ui/screen.json), lifted a little so dark hues still shine. */
    private static final Map<String, Integer> COLOURS =
            Map.of(
                    "sirenite", 0x2074E9,
                    "gehennite", 0xE95C20,
                    "stratite", 0xD48050,
                    "zephyrite", 0x20E9C1,
                    "quetzalite", 0x31E920,
                    "atenite", 0xE9C820,
                    "oculite", 0x9520E9);

    private static final Map<String, RenderType> TYPES = new HashMap<>();

    /**
     * Every gem's glint type, for the fixed render buffers (RegisterRenderBuffersEvent): a glint
     * must be drawn after the item it lies on (it depth-tests EQUAL), which only the fixed
     * buffers guarantee; a shared buffer can flush it before the item and it never shows.
     */
    public static java.util.List<RenderType> all() {
        var out = new java.util.ArrayList<RenderType>();
        COLOURS.forEach((gem, c) -> out.add(TYPES.computeIfAbsent(gem, k -> make(k, c))));
        return out;
    }

    /** The current item being drawn (set around ItemRenderer.render). */
    public static ItemStack current = ItemStack.EMPTY;

    /** The tinted glint for the item being drawn, or null for vanilla's. */
    public static RenderType forCurrent() {
        if (current.isEmpty()) return null;
        var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(current.getItem());
        if (!"ethermod".equals(id.getNamespace())) return null;
        Integer c = COLOURS.get(id.getPath());
        if (c == null) return null;
        return TYPES.computeIfAbsent(id.getPath(), k -> make(k, c));
    }

    private static float lift(int c, int shift) {
        return Math.min(1f, ((c >> shift) & 255) / 255f * 1.25f + .08f);
    }

    private static RenderType make(String gem, int c) {
        float r = lift(c, 16), g = lift(c, 8), b = lift(c, 0);
        var tint =
                new TexturingStateShard(
                        "ethermod_gem_tint",
                        () -> {
                            GLINT_TEXTURING.setupRenderState();
                            RenderSystem.setShaderColor(r, g, b, 1f);
                        },
                        () -> {
                            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                            GLINT_TEXTURING.clearRenderState();
                        });
        return create(
                "ethermod_gem_glint_" + gem,
                DefaultVertexFormat.POSITION_TEX,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                false,
                CompositeState.builder()
                        .setShaderState(RENDERTYPE_GLINT_SHADER)
                        .setTextureState(new TextureStateShard(TEXTURE, true, false))
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(EQUAL_DEPTH_TEST)
                        .setTransparencyState(GLINT_TRANSPARENCY)
                        .setTexturingState(tint)
                        .createCompositeState(false));
    }
}
