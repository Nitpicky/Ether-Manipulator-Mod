package com.mattermod.mixin;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Pyrotechnics' Forge Heart fuels furnaces directly. */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface FurnaceAccessor {
    @Accessor("litTime")
    int mattermod$getLitTime();

    @Accessor("litTime")
    void mattermod$setLitTime(int ticks);

    @Accessor("litDuration")
    void mattermod$setLitDuration(int ticks);
}
