package dev.claudecraft.chat;

import dev.claudecraft.ClaudeCraftPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Lets players talk to Claude by prefixing a chat message with the configured
 * trigger (default "@claude"), e.g. "@claude how do I make a beacon?".
 */
public final class ChatListener implements Listener {

    private final ClaudeCraftPlugin plugin;
    private final ChatService chatService;

    public ChatListener(ClaudeCraftPlugin plugin, ChatService chatService) {
        this.plugin = plugin;
        this.chatService = chatService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        String trigger = plugin.getConfig().getString("chat.trigger", "@claude");
        if (trigger == null || trigger.isBlank()) {
            return;
        }
        String message = event.getMessage();
        if (!message.toLowerCase().startsWith(trigger.toLowerCase())) {
            return;
        }
        if (!event.getPlayer().hasPermission("claudecraft.ask")) {
            return;
        }
        String question = message.substring(trigger.length()).trim();
        if (question.isEmpty()) {
            return;
        }
        chatService.ask(event.getPlayer(), question);
    }
}
