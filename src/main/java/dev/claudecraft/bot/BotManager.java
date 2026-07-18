package dev.claudecraft.bot;

import dev.claudecraft.ClaudeCraftPlugin;
import dev.claudecraft.LlmService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Owns the bot entity and the currently running agent task.
 */
public final class BotManager {

    private final ClaudeCraftPlugin plugin;
    private final LlmService llm;
    private UUID botId;
    private BotAgent agent;

    public BotManager(ClaudeCraftPlugin plugin, LlmService llm) {
        this.plugin = plugin;
        this.llm = llm;
    }

    // ------------------------------------------------------------------
    // Entity lifecycle (main thread only)
    // ------------------------------------------------------------------

    public LivingEntity getBot() {
        if (botId == null) {
            return null;
        }
        Entity e = Bukkit.getEntity(botId);
        return (e instanceof LivingEntity living && living.isValid()) ? living : null;
    }

    public String spawn(Player near) {
        if (getBot() != null) {
            return "The bot is already spawned. Use /bot despawn first.";
        }
        EntityType type;
        try {
            type = EntityType.valueOf(plugin.getConfig().getString("bot.entity-type", "VILLAGER").toUpperCase());
        } catch (IllegalArgumentException e) {
            type = EntityType.VILLAGER;
        }
        org.bukkit.util.Vector facing = near.getLocation().getDirection().setY(0);
        if (facing.lengthSquared() < 0.01) {
            facing = new org.bukkit.util.Vector(1, 0, 0);
        }
        Location loc = near.getLocation().clone().add(facing.normalize().multiply(2.0));
        loc.setY(near.getLocation().getY());
        Entity entity = near.getWorld().spawnEntity(loc, type);
        if (!(entity instanceof LivingEntity living)) {
            entity.remove();
            return "Configured bot.entity-type is not a living entity.";
        }
        living.setCustomName(botDisplayName());
        living.setCustomNameVisible(true);
        living.setInvulnerable(true);
        living.setSilent(true);
        living.setPersistent(true);
        living.setCollidable(false);
        living.setGravity(false);
        if (living instanceof Mob mob) {
            mob.setAI(false);
            mob.setRemoveWhenFarAway(false);
        }
        botId = living.getUniqueId();
        broadcast("Reporting for duty! Give me a task with /bot task <description>.");
        return null;
    }

    public String despawn() {
        stopTask();
        LivingEntity bot = getBot();
        if (bot == null) {
            botId = null;
            return "No bot is spawned.";
        }
        bot.remove();
        botId = null;
        return null;
    }

    // ------------------------------------------------------------------
    // Tasks
    // ------------------------------------------------------------------

    public String startTask(String requester, String task) {
        if (getBot() == null) {
            return "Spawn the bot first with /bot spawn.";
        }
        if (agent != null) {
            return "The bot is already working on a task. Use /bot stop first.";
        }
        BotTools tools = new BotTools(plugin, this);
        agent = new BotAgent(plugin, llm, this, tools, task, requester, () -> agent = null);
        plugin.executor().submit(agent);
        return null;
    }

    public String stopTask() {
        if (agent == null) {
            return "The bot is not working on anything.";
        }
        agent.cancel();
        return null;
    }

    public String status() {
        if (getBot() == null) {
            return "Bot: not spawned.";
        }
        LivingEntity bot = getBot();
        Location l = bot.getLocation();
        String pos = "x=" + l.getBlockX() + " y=" + l.getBlockY() + " z=" + l.getBlockZ();
        if (agent != null) {
            return "Bot: spawned at " + pos + ", working on: " + agent.task();
        }
        return "Bot: spawned at " + pos + ", idle.";
    }

    public void shutdown() {
        if (agent != null) {
            agent.cancel();
        }
        LivingEntity bot = getBot();
        if (bot != null) {
            bot.remove();
        }
        botId = null;
    }

    // ------------------------------------------------------------------
    // Chat + config helpers
    // ------------------------------------------------------------------

    public void botSay(String message) {
        broadcast(message);
    }

    /** Thread-safe broadcast with the bot's chat prefix. */
    public void broadcast(String message) {
        String line = ChatColor.GOLD + "<" + botName() + "> " + ChatColor.RESET + message;
        if (Bukkit.isPrimaryThread()) {
            Bukkit.broadcastMessage(line);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcastMessage(line));
        }
    }

    public String botName() {
        return plugin.getConfig().getString("bot.name", "Claude");
    }

    private String botDisplayName() {
        return ChatColor.GOLD + botName() + ChatColor.YELLOW + " (AI)";
    }

    public int maxBuildRadius() {
        return plugin.getConfig().getInt("bot.max-build-radius", 48);
    }

    public int maxBlocksPerTask() {
        return plugin.getConfig().getInt("bot.max-blocks-per-task", 1000);
    }

    public boolean allowBreaking() {
        return plugin.getConfig().getBoolean("bot.allow-breaking", true);
    }
}
