package com.mattermod.client;

import com.mattermod.MatterMod;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Where the glowing effects (beam, sparks, glows, orbs) get drawn.
 *
 * <p>Without a shader pack: the vanilla lightning type (additive, full bright). With a shader pack
 * the lightning type goes through the pack's "lightning" program, which most packs light, fog and
 * blur like a cloud, so the beam came out as a grey smear. With a pack active the effects use the
 * translucent beacon-beam type instead (plain white texture, vertex colour): every pack draws it as
 * an emissive glow. Callers only set position and colour; the wrapper fills in the rest.
 */
@OnlyIn(Dist.CLIENT)
public final class FxRender {

    private FxRender() {}

    private static final ResourceLocation WHITE =
            ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, "textures/misc/white.png");
    /** The translucent beacon-beam type, but drawn from both sides (ribbons face any way). */
    private static final RenderType SHADER_SAFE =
            RenderType.create(
                    "mattermod_fx_shader_safe",
                    com.mojang.blaze3d.vertex.DefaultVertexFormat.BLOCK,
                    com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS,
                    1536,
                    false,
                    true,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_BEACON_BEAM_SHADER)
                            .setTextureState(
                                    new RenderStateShard.TextureStateShard(WHITE, false, false))
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                            .createCompositeState(false));

    public static RenderType type() {
        return ShaderCompat.shadersActive() ? SHADER_SAFE : RenderType.lightning();
    }

    public static VertexConsumer buffer(MultiBufferSource buf) {
        RenderType t = type();
        VertexConsumer vc = buf.getBuffer(t);
        return t == SHADER_SAFE ? new Filled(vc) : vc;
    }

    public static void end(MultiBufferSource.BufferSource buf) {
        buf.endBatch(type());
    }

    /** Adds the UV, light and normal the beacon-beam format needs after each colour. */
    private record Filled(VertexConsumer d) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            d.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            d.setColor(r, g, b, a).setUv(0.5f, 0.5f).setUv2(240, 240).setNormal(0f, 1f, 0f);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }
}
