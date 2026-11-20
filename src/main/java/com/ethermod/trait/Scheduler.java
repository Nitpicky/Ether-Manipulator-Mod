package com.ethermod.trait;

import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Server-side delayed tasks, ticked per level. */
public final class Scheduler {

    private Scheduler() {}

    private record Task(long at, Runnable run, boolean runOnStop) {}

    private static final Map<ServerLevel, List<Task>> TASKS = new HashMap<>();

    /**
     * Runs {@code r} on the server after {@code delay} ticks (dropped if the server stops first).
     */
    public static void schedule(ServerLevel level, int delay, Runnable r) {
        schedule(level, delay, r, false);
    }

    /**
     * As {@link #schedule(ServerLevel, int, Runnable)}; with {@code runOnStop} the task runs early
     * when the server stops instead of being dropped (clean-ups that must not be skipped).
     */
    public static void schedule(ServerLevel level, int delay, Runnable r, boolean runOnStop) {
        TASKS.computeIfAbsent(level, k -> new ArrayList<>())
                .add(new Task(level.getGameTime() + delay, r, runOnStop));
    }

    static void tick(ServerLevel level, long now) {
        List<Task> tasks = TASKS.get(level);
        if (tasks != null && !tasks.isEmpty()) {
            List<Task> due = new ArrayList<>();
            tasks.removeIf(
                    t -> {
                        if (t.at() <= now) {
                            due.add(t);
                            return true;
                        }
                        return false;
                    });
            for (Task t : due) t.run().run();
        }
    }

    /** Runs the runOnStop tasks, then drops everything. */
    static void runOnStopAndClear() {
        // clean-ups such as shot fluids drying up would otherwise leave permanent blocks
        TASKS.values()
                .forEach(
                        list ->
                                list.stream()
                                        .filter(Task::runOnStop)
                                        .forEach(
                                                t -> {
                                                    try {
                                                        t.run().run();
                                                    } catch (RuntimeException e) {
                                                        com.ethermod.EtherMod.LOGGER.warn(
                                                                "Clean-up task failed on stop", e);
                                                    }
                                                }));
        TASKS.clear();
    }
}
