package com.ethermod.client;

import com.ethermod.beam.Muzzle;
import com.ethermod.item.ManipulatorColors;
import com.ethermod.item.EtherManipulatorItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoItemRenderer;

@OnlyIn(Dist.CLIENT)
public class ManipulatorItemRenderer extends GeoItemRenderer<EtherManipulatorItem> {

    private static final String MUZZLE_BONE = "muzzle_anchor";

    public ManipulatorItemRenderer() {
        super(new ManipulatorItemModel());
        addRenderLayer(new ManipulatorTintLayer(this));
    }

    /** The texture recoloured for the stack's body colour (the painted one by default). */
    @Override
    public ResourceLocation getTextureLocation(EtherManipulatorItem animatable) {
        var stack = getCurrentItemStack();
        return stack == null || stack.isEmpty()
                ? BodyTextures.BASE
                : BodyTextures.get(
                        ManipulatorColors.body(stack),
                        com.ethermod.item.ManipulatorStage.get(stack));
    }

    @Override
    public void renderRecursively(
            PoseStack poseStack,
            EtherManipulatorItem animatable,
            GeoBone bone,
            RenderType renderType,
            MultiBufferSource bufferSource,
            VertexConsumer buffer,
            boolean isReRender,
            float partialTick,
            int packedLight,
            int packedOverlay,
            int colour) {

        if (!isReRender && MUZZLE_BONE.equals(bone.getName()) && !ShaderCompat.inShadowPass()) {
            ItemDisplayContext ctx = this.renderPerspective;
            boolean fp =
                    ctx == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                            || ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
            boolean tp =
                    ctx == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                            || ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
            if (fp || tp) {
                // The bone pivot is the centre of the nozzle's front face (model units are pixels)
                Muzzle.capture(
                        poseStack.last().pose(),
                        bone.getPivotX() / 16f,
                        bone.getPivotY() / 16f,
                        bone.getPivotZ() / 16f,
                        fp);
            }
        }

        super.renderRecursively(
                poseStack,
                animatable,
                bone,
                renderType,
                bufferSource,
                buffer,
                isReRender,
                partialTick,
                packedLight,
                packedOverlay,
                colour);
    }
}
