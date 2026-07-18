package dev.claudecraft;

import dev.claudecraft.bot.BotManager;
import dev.claudecraft.chat.ChatListener;
import dev.claudecraft.chat.ChatService;
import dev.claudecraft.command.AskCommand;
import dev.claudecraft.command.BotCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ClaudeCraftPlugin extends JavaPlugin {

    private LlmService llmService;
    private ChatService chatService;
    private BotManager botManager;
    private ExecutorService executor;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ClaudeCraft-LLM");
            t.setDaemon(true);
            return t;
        });

        try {
            llmService = new LlmService(this);
        } catch (Exception e) {
            getLogger().severe("Failed to initialise the Anthropic client: " + e.getMessage());
            getLogger().severe("Set api-key in config.yml or the ANTHROPIC_API_KEY environment variable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        chatService = new ChatService(this, llmService);
        botManager = new BotManager(this, llmService);

        Objects.requireNonNull(getCommand("ask")).setExecutor(new AskCommand(chatService));
        BotCommand botCommand = new BotCommand(botManager);
        Objects.requireNonNull(getCommand("bot")).setExecutor(botCommand);
        Objects.requireNonNull(getCommand("bot")).setTabCompleter(botCommand);
        getServer().getPluginManager().registerEvents(new ChatListener(this, chatService), this);

        getLogger().info("ClaudeCraft enabled. Model: " + getConfig().getString("model"));
    }

    @Override
    public void onDisable() {
        if (botManager != null) {
            botManager.shutdown();
        }
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public ExecutorService executor() {
        return executor;
    }

    public LlmService llm() {
        return llmService;
    }

    public BotManager bots() {
        return botManager;
    }
}
