package dev.claudecraft.command;

import dev.claudecraft.chat.ChatService;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class AskCommand implements CommandExecutor {

    private final ChatService chatService;

    public AskCommand(ChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can chat with Claude.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(ChatColor.YELLOW + "Usage: /" + label + " <message>");
            return true;
        }
        chatService.ask(player, String.join(" ", args));
        return true;
    }
}
