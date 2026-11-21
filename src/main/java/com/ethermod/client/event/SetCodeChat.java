package com.ethermod.client.event;

import com.ethermod.EtherMod;
import com.ethermod.config.ClientConfig;
import com.ethermod.trait.BuildCode;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Set codes in chat (S_1GABA2HAAA) become links: click copies the code, hover shows what it holds.
 * Client only, so it works on any server. Only valid codes are linked; the rest of the message keeps
 * its own styles.
 */
@EventBusSubscriber(modid = EtherMod.MODID, value = Dist.CLIENT)
public final class SetCodeChat {
    private SetCodeChat() {}

    private static final Pattern CODE =
            Pattern.compile("(?i)(?<![A-Z0-9_])" + BuildCode.PREFIX + "(?:[1-3][A-Z][AB]{0,3}){1,3}(?![A-Z0-9_])");

    @SubscribeEvent
    static void onChat(ClientChatReceivedEvent event) {
        if (!ClientConfig.chatSetCodes()) return;
        var level = Minecraft.getInstance().level;
        if (level == null || !CODE.matcher(event.getMessage().getString()).find()) return;
        var access = level.registryAccess();
        record Part(Style style, String text) {}
        List<Part> parts = new ArrayList<>();
        event.getMessage().visit((style, text) -> {
            parts.add(new Part(style, text));
            return Optional.empty();
        }, Style.EMPTY);
        MutableComponent out = Component.empty();
        boolean linked = false;
        for (Part part : parts) {
            var m = CODE.matcher(part.text());
            int at = 0;
            while (m.find()) {
                String code = m.group();
                Component hover = describe(code, access);
                if (hover == null) continue;
                if (m.start() > at) out.append(Component.literal(part.text().substring(at, m.start())).setStyle(part.style()));
                out.append(
                        Component.literal(code)
                                .setStyle(
                                        part.style()
                                                .withColor(ChatFormatting.AQUA)
                                                .withUnderlined(true)
                                                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, code))
                                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover))));
                at = m.end();
                linked = true;
            }
            if (at < part.text().length()) out.append(Component.literal(part.text().substring(at)).setStyle(part.style()));
        }
        if (linked) event.setMessage(out);
    }

    /** "Tectonic A B" per slot, then "Click to copy"; null when the code isn't a real set. */
    private static Component describe(String code, net.minecraft.core.RegistryAccess access) {
        List<BuildCode.Slot> slots;
        try {
            BuildCode.decode(code, access);
            slots = BuildCode.parse(code);
        } catch (IllegalArgumentException e) {
            return null;
        }
        MutableComponent text = Component.empty();
        for (var slot : slots) {
            text.append(Component.translatable("module." + EtherMod.MODID + "." + slot.module()).withStyle(ChatFormatting.WHITE));
            for (int choice : slot.choices()) text.append(Component.literal(choice == 0 ? " A" : " B").withStyle(ChatFormatting.GRAY));
            text.append("\n");
        }
        return text.append(Component.translatable("chat.copy.click").withStyle(ChatFormatting.AQUA));
    }
}
