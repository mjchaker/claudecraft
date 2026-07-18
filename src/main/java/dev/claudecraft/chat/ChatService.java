package dev.claudecraft.chat;

import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import dev.claudecraft.ClaudeCraftPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Per-player conversations with Claude over in-game chat.
 */
public final class ChatService {

    private record Turn(boolean user, String text) {}

    private final ClaudeCraftPlugin plugin;
    private final dev.claudecraft.LlmService llm;
    private final Map<UUID, Deque<Turn>> histories = new ConcurrentHashMap<>();

    public ChatService(ClaudeCraftPlugin plugin, dev.claudecraft.LlmService llm) {
        this.plugin = plugin;
        this.llm = llm;
    }

    /** Handles a player message asynchronously; never blocks the calling thread on the API. */
    public void ask(Player player, String message) {
        UUID id = player.getUniqueId();
        player.sendMessage(ChatColor.GRAY + "" + ChatColor.ITALIC + "Claude is thinking...");
        plugin.executor().submit(() -> {
            try {
                String reply = request(player, message);
                Deque<Turn> history = histories.computeIfAbsent(id, k -> new ArrayDeque<>());
                synchronized (history) {
                    history.addLast(new Turn(true, message));
                    history.addLast(new Turn(false, reply));
                    int limit = plugin.getConfig().getInt("chat.history-limit", 10) * 2;
                    while (history.size() > limit) {
                        history.pollFirst();
                    }
                }
                deliver(player, reply);
            } catch (Throwable e) {
                plugin.getLogger().warning("Chat request failed: " + e);
                sendSync(player, ChatColor.RED + "[Claude] Sorry, something went wrong talking to the API.");
            }
        });
    }

    public void clearHistory(UUID player) {
        histories.remove(player);
    }

    private String request(Player player, String message) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(llm.model())
                .maxTokens(plugin.getConfig().getLong("chat.max-tokens", 1500L))
                .system(plugin.getConfig().getString("chat.system-prompt", "You are a helpful Minecraft assistant.")
                        + "\nYou are currently talking to the player named " + player.getName() + ".")
                .thinking(ThinkingConfigAdaptive.builder().build());

        Deque<Turn> history = histories.get(player.getUniqueId());
        if (history != null) {
            List<Turn> snapshot;
            synchronized (history) {
                snapshot = new ArrayList<>(history);
            }
            for (Turn t : snapshot) {
                if (t.user()) {
                    b.addUserMessage(t.text());
                } else {
                    b.addAssistantMessage(t.text());
                }
            }
        }
        b.addUserMessage(message);

        Message response = llm.client().messages().create(b.build());
        if (response.stopReason().isPresent()
                && response.stopReason().get().toString().equalsIgnoreCase("refusal")) {
            return "I can't help with that one.";
        }
        String text = response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining("\n"))
                .trim();
        return text.isEmpty() ? "(no reply)" : text;
    }

    private void deliver(Player player, String reply) {
        boolean broadcast = plugin.getConfig().getBoolean("chat.broadcast", false);
        String prefix = ChatColor.LIGHT_PURPLE + "[Claude] " + ChatColor.RESET;
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (String line : reply.split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                if (broadcast) {
                    Bukkit.broadcastMessage(prefix + line);
                } else if (player.isOnline()) {
                    player.sendMessage(prefix + line);
                }
            }
        });
    }

    private void sendSync(Player player, String message) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendMessage(message);
            }
        });
    }
}
