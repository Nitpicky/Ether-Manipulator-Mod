package com.mattermod.event;

import com.mattermod.item.*;
import com.mattermod.manipulator.Manipulator;
import com.mattermod.trait.*;

import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class RedesignCommands {
    @SubscribeEvent
    public static void register(RegisterCommandsEvent ev) {
        ev.getDispatcher()
                .register(
                        Commands.literal("mattermod")
                                .requires(s -> s.hasPermission(2))
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
