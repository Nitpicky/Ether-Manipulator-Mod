package com.ethermod.mixin;

import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.*;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FlowingFluid.class)
public abstract class FluidPocketMixin {
    @Inject(method = "getFlow", at = @At("HEAD"), cancellable = true)
    private void mm$current(
            BlockGetter level,
            BlockPos pos,
            FluidState state,
            CallbackInfoReturnable<net.minecraft.world.phys.Vec3> cir) {
        if (state.is(net.minecraft.tags.FluidTags.WATER)) {
            var flow = com.ethermod.trait.RapidsCurrents.flow(level, pos);
            if (flow != null) cir.setReturnValue(flow);
        }
    }

    @Inject(method = "canSpreadTo", at = @At("HEAD"), cancellable = true)
    private void mm$air(
            BlockGetter level,
            BlockPos from,
            BlockState fromState,
            Direction direction,
            BlockPos to,
            BlockState toState,
            FluidState state,
            Fluid fluid,
            CallbackInfoReturnable<Boolean> cir) {
        if (level instanceof net.minecraft.server.level.ServerLevel sl
                && com.ethermod.trait.TempBlocks.isAirPocket(sl, to)) cir.setReturnValue(false);
    }
}
