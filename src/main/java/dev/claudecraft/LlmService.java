package dev.claudecraft;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;

/**
 * The connector to the Anthropic API. Builds a single shared client from the
 * plugin config (falling back to the ANTHROPIC_API_KEY environment variable).
 */
public final class LlmService {

    private final AnthropicClient client;
    private final String model;

    public LlmService(ClaudeCraftPlugin plugin) {
        String key = plugin.getConfig().getString("api-key", "");
        if (key == null || key.isBlank()) {
            key = System.getenv("ANTHROPIC_API_KEY");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("No Anthropic API key configured");
        }
        this.client = AnthropicOkHttpClient.builder().apiKey(key).build();
        this.model = plugin.getConfig().getString("model", "claude-opus-4-8");
    }

    public AnthropicClient client() {
        return client;
    }

    public String model() {
        return model;
    }
}
