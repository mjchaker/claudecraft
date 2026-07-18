package dev.claudecraft.bot;

import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.RedactedThinkingBlockParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingBlockParam;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import dev.claudecraft.ClaudeCraftPlugin;
import dev.claudecraft.LlmService;

import java.util.ArrayList;
import java.util.List;

/**
 * The agentic loop: sends the task to Claude with the bot's tools, executes
 * tool calls against the world, and feeds results back until the model is
 * done. Runs entirely on a worker thread; world access happens inside
 * {@link BotTools} on the main thread.
 */
public final class BotAgent implements Runnable {

    private final ClaudeCraftPlugin plugin;
    private final LlmService llm;
    private final BotManager manager;
    private final BotTools tools;
    private final String task;
    private final String requester;
    private final Runnable onFinish;
    private volatile boolean cancelled;

    public BotAgent(ClaudeCraftPlugin plugin, LlmService llm, BotManager manager,
                    BotTools tools, String task, String requester, Runnable onFinish) {
        this.plugin = plugin;
        this.llm = llm;
        this.manager = manager;
        this.tools = tools;
        this.task = task;
        this.requester = requester;
        this.onFinish = onFinish;
    }

    public void cancel() {
        cancelled = true;
    }

    public String task() {
        return task;
    }

    @Override
    public void run() {
        try {
            tools.resetBudget();
            runLoop();
        } catch (Throwable e) {
            plugin.getLogger().warning("Bot task failed: " + e);
            manager.broadcast("The bot ran into a problem and stopped: " + e.getMessage());
        } finally {
            onFinish.run();
        }
    }

    private void runLoop() {
        List<Tool> toolDefs = tools.definitions();
        List<MessageParam> messages = new ArrayList<>();
        messages.add(MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content("Player " + requester + " has given you this task:\n\n" + task
                        + "\n\nStart by observing your surroundings, then plan and execute the task. "
                        + "When you are completely done, use say() to announce the result and stop.")
                .build());

        int maxIterations = plugin.getConfig().getInt("bot.max-iterations", 40);
        boolean narrate = plugin.getConfig().getBoolean("bot.narrate", true);
        String systemPrompt = plugin.getConfig().getString("bot.system-prompt", "You are a Minecraft bot.");
        long maxTokens = plugin.getConfig().getLong("bot.max-tokens", 16000L);

        for (int i = 0; i < maxIterations; i++) {
            if (cancelled) {
                manager.broadcast("Task cancelled.");
                return;
            }

            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(llm.model())
                    .maxTokens(maxTokens)
                    .system(systemPrompt)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .messages(messages);
            for (Tool t : toolDefs) {
                params.addTool(t);
            }

            Message response = llm.client().messages().create(params.build());

            List<ContentBlockParam> assistantEcho = new ArrayList<>();
            List<ContentBlockParam> toolResults = new ArrayList<>();

            for (ContentBlock block : response.content()) {
                block.text().ifPresent(text -> {
                    assistantEcho.add(ContentBlockParam.ofText(
                            TextBlockParam.builder().text(text.text()).build()));
                    if (narrate && !text.text().isBlank()) {
                        manager.broadcast(text.text().trim());
                    }
                });
                block.thinking().ifPresent(thinking -> assistantEcho.add(ContentBlockParam.ofThinking(
                        ThinkingBlockParam.builder()
                                .thinking(thinking.thinking())
                                .signature(thinking.signature())
                                .build())));
                block.redactedThinking().ifPresent(redacted -> assistantEcho.add(
                        ContentBlockParam.ofRedactedThinking(
                                RedactedThinkingBlockParam.builder().data(redacted.data()).build())));
                block.toolUse().ifPresent(toolUse -> {
                    assistantEcho.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                            .id(toolUse.id())
                            .name(toolUse.name())
                            .input(toolUse._input())
                            .build()));
                    String result = cancelled
                            ? "Task was cancelled by an operator. Stop working now."
                            : tools.execute(toolUse.name(), toolUse._input());
                    toolResults.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(toolUse.id())
                            .content(result)
                            .build()));
                });
            }

            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.ASSISTANT)
                    .contentOfBlockParams(assistantEcho)
                    .build());

            boolean wantsTools = response.stopReason()
                    .map(sr -> sr.equals(StopReason.TOOL_USE))
                    .orElse(false);
            if (!wantsTools || toolResults.isEmpty()) {
                boolean refused = response.stopReason()
                        .map(sr -> sr.equals(StopReason.REFUSAL))
                        .orElse(false);
                if (refused) {
                    manager.broadcast("I can't do that task, sorry.");
                }
                return; // done
            }

            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(toolResults)
                    .build());
        }

        manager.broadcast("I hit my step limit for this task - stopping here.");
    }
}
