package com.ethermod.event;

import com.ethermod.item.*;
import com.ethermod.manipulator.Manipulator;
import com.ethermod.trait.*;

import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class RedesignCommands {
    @SubscribeEvent
    public static void register(RegisterCommandsEvent ev) {
        ev.getDispatcher()
                .register(
                        Commands.literal("ethermod")
                                .requires(s -> s.hasPermission(2))
                                .then(
                                        // hands out the Manipulator when give_on_first_join is off (quests, rewards)
                                        Commands.literal("grant")
                                                .then(
                                                        Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.players())
                                                                .executes(
                                                                        c -> {
                                                                            var ps = net.minecraft.commands.arguments.EntityArgument.getPlayers(c, "targets");
                                                                            for (var p : ps) {
                                                                                var d = Manipulator.data(p);
                                                                                d.setOwned(true);
                                                                                if (!d.greeted()) {
                                                                                    d.setGreeted(true);
                                                                                    p.displayClientMessage(
                                                                                            net.minecraft.network.chat.Component.translatable("message.ethermod.intro")
                                                                                                    .withStyle(net.minecraft.ChatFormatting.AQUA)
                                                                                                    .append(net.minecraft.network.chat.Component.translatable("message.ethermod.intro_keys")
                                                                                                            .withStyle(net.minecraft.ChatFormatting.GRAY)),
                                                                                            false);
                                                                                }
                                                                                Manipulator.sync(p);
                                                                            }
                                                                            return ps.size();
                                                                        })))
                                .then(
                                        // one Module for the given players, exactly as its gem would
                                        // (advancement, cue): for quests and modpack items
                                        Commands.literal("unlock")
                                                .then(
                                                        Commands.argument("targets", net.minecraft.commands.arguments.EntityArgument.players())
                                                                .then(
                                                                        Commands.argument("module", net.minecraft.commands.arguments.ResourceLocationArgument.id())
                                                                                .suggests(
                                                                                        (c, b) ->
                                                                                                net.minecraft.commands.SharedSuggestionProvider.suggestResource(
                                                                                                        TraitLines.all(c.getSource().registryAccess()).stream()
                                                                                                                .map(java.util.Map.Entry::getKey)
                                                                                                                .filter(k -> !k.equals(Traits.PLASMIC) && !k.equals(Traits.KINETIC)),
                                                                                                        b))
                                                                                .executes(
                                                                                        c -> {
                                                                                            var id = net.minecraft.commands.arguments.ResourceLocationArgument.getId(c, "module");
                                                                                            if (!id.getNamespace().equals("ethermod"))
                                                                                                id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ethermod", id.getPath());
                                                                                            final var mod = id;
                                                                                            boolean exists =
                                                                                                    TraitLines.all(c.getSource().registryAccess()).stream()
                                                                                                            .anyMatch(e -> e.getKey().equals(mod));
                                                                                            if (!exists)
                                                                                                throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(
                                                                                                                net.minecraft.network.chat.Component.translatable("argument.id.invalid"))
                                                                                                        .create();
                                                                                            int n = 0;
                                                                                            for (var p : net.minecraft.commands.arguments.EntityArgument.getPlayers(c, "targets"))
                                                                                                if (ModuleItem.grant(p, mod)) n++;
                                                                                            return n;
                                                                                        }))))
                                .then(
                                        Commands.literal("unlock_all")
                                                .executes(
                                                        c -> {
                                                            var p =
                                                                    c.getSource()
                                                                            .getPlayerOrException();
                                                            var st = Manipulator.stack(p);
                                                            for (var e :
                                                                    TraitLines.all(
                                                                            p.registryAccess()))
                                                                ModuleUnlocks.unlock(
                                                                        st, e.getKey());
                                                            Manipulator.sync(p);
                                                            return 1;
                                                        }))
                                .then(
                                        Commands.literal("overclocked")
                                                .executes(
                                                        c -> {
                                                            var p =
                                                                    c.getSource()
                                                                            .getPlayerOrException();
                                                            ManipulatorStage.set(
                                                                    Manipulator.stack(p),
                                                                    ManipulatorStage.NETHERITE);
                                                            Manipulator.sync(p);
                                                            return 1;
                                                        }))
                                .then(
                                        Commands.literal("refill")
                                                .executes(
                                                        c -> {
                                                            var p =
                                                                    c.getSource()
                                                                            .getPlayerOrException();
                                                            var st = Manipulator.stack(p);
                                                            OrbMode.setTank(
                                                                    st, OrbMode.capacity(st));
                                                            Manipulator.sync(p);
                                                            return 1;
                                                        })));
    }

    /** Items listed in the server config's gem_items unlock their Module like that gem. */
    @SubscribeEvent
    public static void gemItem(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem ev) {
        var stack = ev.getItemStack();
        if (stack.getItem() instanceof ModuleItem) return;
        String module = com.ethermod.config.ServerConfig.gemModule(stack);
        if (module == null) return;
        var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("ethermod", module);
        if (id.equals(Traits.PLASMIC) || id.equals(Traits.KINETIC)) return;
        if (!(ev.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp)) return;
        if (!Manipulator.data(sp).owned()) return;
        if (TraitLines.all(sp.registryAccess()).stream().noneMatch(e -> e.getKey().equals(id))) return;
        // already known: the item keeps its own use
        if (com.ethermod.trait.ModuleUnlocks.known(Manipulator.stack(sp), id)) return;
        if (ModuleItem.grant(sp, id)) {
            stack.consume(1, sp);
            ev.setCanceled(true);
            ev.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        }
    }
}
