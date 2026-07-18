package dev.claudecraft.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Runs world-touching code on the main server thread from the LLM worker
 * thread. Bukkit's API is not thread-safe; every tool the agent executes goes
 * through here.
 */
public final class Sync {

    private Sync() {}

    public static <T> T call(Plugin plugin, Callable<T> task) throws Exception {
        if (Bukkit.isPrimaryThread()) {
            return task.call();
        }
        return Bukkit.getScheduler().callSyncMethod(plugin, task).get(15, TimeUnit.SECONDS);
    }

    public static void run(Plugin plugin, Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
