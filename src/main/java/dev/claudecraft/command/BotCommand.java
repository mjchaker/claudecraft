package dev.claudecraft.command;

import dev.claudecraft.bot.BotManager;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public final class BotCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("spawn", "despawn", "task", "stop", "say", "status");

    private final BotManager botManager;

    public BotCommand(BotManager botManager) {
        this.botManager = botManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        String sub = args[0].toLowerCase();
        String rest = args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length))
                : "";

        String error = switch (sub) {
            case "spawn" -> {
                if (!(sender instanceof Player player)) {
                    yield "Only players can spawn the bot (it spawns next to you).";
                }
                yield botManager.spawn(player);
            }
            case "despawn" -> botManager.despawn();
            case "task" -> {
                if (rest.isBlank()) {
                    yield "Usage: /bot task <description of what to do or build>";
                }
                String err = botManager.startTask(sender.getName(), rest);
                if (err == null) {
                    sender.sendMessage(ChatColor.GREEN + "Task given to the bot: " + ChatColor.RESET + rest);
                }
                yield err;
            }
            case "stop" -> {
                String err = botManager.stopTask();
                if (err == null) {
                    sender.sendMessage(ChatColor.YELLOW + "Asked the bot to stop after its current step.");
                }
                yield err;
            }
            case "say" -> {
                if (rest.isBlank()) {
                    yield "Usage: /bot say <message>";
                }
                botManager.botSay(rest);
                yield null;
            }
            case "status" -> {
                sender.sendMessage(ChatColor.AQUA + botManager.status());
                yield null;
            }
            default -> {
                usage(sender);
                yield null;
            }
        };

        if (error != null) {
            sender.sendMessage(ChatColor.RED + error);
        }
        return true;
    }

    private void usage(CommandSender sender) {
        sender.sendMessage(ChatColor.AQUA + "ClaudeCraft bot commands:");
        sender.sendMessage(ChatColor.GRAY + "/bot spawn " + ChatColor.WHITE + "- spawn the bot next to you");
        sender.sendMessage(ChatColor.GRAY + "/bot task <desc> " + ChatColor.WHITE + "- give it a job (e.g. 'build a small oak cabin near me')");
        sender.sendMessage(ChatColor.GRAY + "/bot stop " + ChatColor.WHITE + "- cancel the current task");
        sender.sendMessage(ChatColor.GRAY + "/bot status " + ChatColor.WHITE + "- what is it doing?");
        sender.sendMessage(ChatColor.GRAY + "/bot say <msg> " + ChatColor.WHITE + "- make it talk");
        sender.sendMessage(ChatColor.GRAY + "/bot despawn " + ChatColor.WHITE + "- remove the bot");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
