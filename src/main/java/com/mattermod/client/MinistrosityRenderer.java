package com.mattermod.client;

import com.mattermod.MatterMod;
import com.mattermod.entity.Ministrosity;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.model.DefaultedEntityGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/** Ministrosity: the Golem's little helper, its redstone showing its Golem's generator (GolemSignalLayer). */
public final class MinistrosityRenderer extends GeoEntityRenderer<Ministrosity> {

    public MinistrosityRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new DefaultedEntityGeoModel<>(ResourceLocation.fromNamespaceAndPath(MatterMod.MODID, "ministrosity")) {
            /** Its head is its whole body: the "look" bone above it turns to what it looks at, like a vanilla mob's head. */
            @Override
            public void setCustomAnimations(Ministrosity m, long id, software.bernie.geckolib.animation.AnimationState<Ministrosity> state) {
                super.setCustomAnimations(m, id, state);
                var look = getAnimationProcessor().getBone("look");
                var data = state.getData(software.bernie.geckolib.constant.DataTickets.ENTITY_MODEL_DATA);
                if (look == null) return;
                boolean free = data != null && m.tickCount > Ministrosity.SPAWN_TICKS && !m.isDeadOrDying();
                look.setRotY(free ? data.netHeadYaw() * net.minecraft.util.Mth.DEG_TO_RAD : 0);
                look.setRotX(free ? data.headPitch() * .5f * net.minecraft.util.Mth.DEG_TO_RAD : 0);
            }
        });
        addRenderLayer(new GolemSignalLayer<>(this, "ministrosity", Ministrosity::linkedGolem));
        addRenderLayer(new EyePulseLayer<>(this, "ministrosity_eyes.png", 30));
        shadowRadius = .4f;
    }
}
