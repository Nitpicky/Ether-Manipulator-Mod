package com.ethermod.registry;

import com.ethermod.EtherMod;
import com.ethermod.manipulator.ManipulatorData;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class ModAttachments {

    private ModAttachments() {}

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, EtherMod.MODID);

    /** The player's Manipulator. Kept through death; summoned state is not saved. */
    public static final Supplier<AttachmentType<ManipulatorData>> MANIPULATOR =
            ATTACHMENTS.register(
                    "manipulator",
                    () ->
                            AttachmentType.builder(() -> new ManipulatorData())
                                    .serialize(ManipulatorData.MAP_CODEC)
                                    .copyOnDeath()
                                    .build());

    public static void register(IEventBus bus) {
        ATTACHMENTS.register(bus);
    }
}
