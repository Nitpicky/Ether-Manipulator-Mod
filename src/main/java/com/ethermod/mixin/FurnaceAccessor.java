package com.ethermod.mixin;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Pyrotechnics' Forge Heart fuels furnaces directly. */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface FurnaceAccessor {
    @Accessor("litTimeRemaining")
    int ethermod$getLitTime();

    @Accessor("litTimeRemaining")
    void ethermod$setLitTime(int ticks);

    @Accessor("litTotalTime")
    void ethermod$setLitDuration(int ticks);
}
