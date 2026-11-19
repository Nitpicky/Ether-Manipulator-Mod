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
}
