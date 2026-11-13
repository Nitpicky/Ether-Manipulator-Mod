package com.mattermod.registry;

import com.mattermod.MatterMod;
import com.mattermod.manipulator.ManipulatorData;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class ModAttachments {

    private ModAttachments() {}

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MatterMod.MODID);

    /** The player's Manipulator. Kept through death; summoned state is not saved. */
    public static final Supplier<AttachmentType<ManipulatorData>> MANIPULATOR =
            ATTACHMENTS.register(
                    "manipulator",
                    () ->
                            AttachmentType.builder(() -> new ManipulatorData())
                                    .serialize(ManipulatorData.CODEC)
                                    .copyOnDeath()
                                    .build());

    public static void register(IEventBus bus) {
        ATTACHMENTS.register(bus);
    }
}
