package com.mattermod.mixin;

import com.mattermod.item.MatterManipulatorItem;
import com.mattermod.trait.Traits;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Third person: the Manipulator itself never moves as a whole there (only first person gets the
 * tool kicks and body reactions), so a charging player's hands tremble instead, harder each charge
 * stage. Only pitch and yaw of the arms: no roll, which would swing the elbows in and out at the
 * shoulder.
 */
@Mixin(HumanoidModel.class)
public abstract class ChargeArmTremblerMixin {

    @Shadow @Final public ModelPart rightArm;
    @Shadow @Final public ModelPart leftArm;

    // a faint tremor: the hands only just shake, harder each stage
    private static final float[] AMP = {0.003f, 0.007f, 0.012f, 0.02f};

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At("TAIL"))
    private void mm$chargeTremble(
            LivingEntity entity,
            float limbSwing,
            float limbSwingAmount,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            CallbackInfo ci) {
        if (!(entity instanceof Player p)
                || !p.isUsingItem()
                || !(p.getUseItem().getItem() instanceof MatterManipulatorItem)) return;
        var mode = com.mattermod.item.OrbMode.get(p.getUseItem()).rmbShot();
        if (mode.isHold()) return; // the tether rope holds still
        int full = Math.max(1, Traits.chargeTicks(p.getUseItem()));
        int held = p.getTicksUsingItem();
        int stage = held > full + 40 ? 3 : Math.min(2, held * 3 / full);
        float a = AMP[stage];
        float t = ageInTicks * (1.6f + stage * 0.5f);
        float dx = a * ((float) Math.sin(t * 1.7f) * 0.6f + (float) Math.sin(t * 3.1f + 1.3f) * 0.4f);
        float dy = a * ((float) Math.sin(t * 2.3f + 0.7f) * 0.6f + (float) Math.sin(t * 3.7f) * 0.4f);
        // both hands hold the tool, so they tremble together
        rightArm.xRot += dx;
        leftArm.xRot += dx;
        rightArm.yRot += dy;
        leftArm.yRot += dy;
    }
}
