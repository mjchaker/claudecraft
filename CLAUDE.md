# ClaudeCraft — development notes

Spigot 1.20.x plugin bridging Minecraft to the Anthropic API: in-game chat with Claude
(`/ask`, `@claude` trigger) plus an agentic builder bot (`/bot task ...`) that observes the
world and builds via a tool-use loop. See `docs/architecture.md` for the full design.

## Build

```sh
mvn package        # requires JDK 17+; output: target/ClaudeCraft-<version>.jar (shaded)
```

## Critical constraints — do not "fix" these

- **Never relocate `com.anthropic` or `kotlin` in the maven-shade config.** Both are Kotlin
  and rely on kotlin-reflect metadata that embeds original class names; relocating either
  breaks the SDK at runtime with `ClassNotFoundException`/`ExceptionInInitializerError`.
  The other deps (Jackson, OkHttp, Okio, slf4j, …) ARE relocated under `dev.claudecraft.libs.*`.
- **maven-shade-plugin must stay ≥ 3.6.0** — older versions fail on Jackson's Java-22
  multi-release class files.
- **All Bukkit/world access must run on the main server thread.** LLM calls run on the
  plugin's executor (`ClaudeCraftPlugin#executor()`); world mutations go through
  `util/Sync.call(...)` / `Sync.run(...)`. Never call Bukkit API directly from the agent loop.
- **Catch `Throwable`, not `Exception`, in async entry points** (`BotAgent#run`,
  `ChatService#ask`). Shading/classloading failures surface as `Error`s and would otherwise
  vanish silently inside the executor.
- **The agent loop must echo `thinking` blocks (with signatures) back in the assistant turn**
  (`BotAgent`) — required by the API when adaptive thinking is combined with tool use.
- Avoid referencing `Material` constants that were renamed across 1.20.x (e.g. `GRASS` →
  `SHORT_GRASS`); `BotTools` uses name-pattern matching for terrain classification instead.

## Layout

- `src/main/java/dev/claudecraft/` — `ClaudeCraftPlugin` (entry), `LlmService` (API client)
  - `chat/` — per-player conversations (`ChatService`), `@claude` chat trigger (`ChatListener`)
  - `bot/` — `BotManager` (entity + task lifecycle), `BotAgent` (tool-use loop),
    `BotTools` (observe / move_to / place_blocks / break_blocks / say)
  - `command/` — `/ask`, `/bot`
- `src/main/resources/config.yml` — defaults for model, prompts, safety rails
  (block budget, build radius, iteration cap)
- `test-client/` — mineflayer headless-client E2E tests (`test.js` infra, `live-test.js` full)

## Testing

There is no unit-test suite; verification is end-to-end against a local Paper server:

1. Create `test-server/` (gitignored) with a Paper 1.20.4 jar, `eula=true`,
   `online-mode=false`, and the built plugin in `plugins/`.
2. Provide a real key via `plugins/ClaudeCraft/config.yml` or `ANTHROPIC_API_KEY`.
3. `cd test-client && npm install && node live-test.js` — joins as "Tester" (needs op via
   server console for `/bot`), exercises chat + a build task, and independently verifies
   placed blocks through the client's world view.

**`test-server/` is gitignored because it contains the API key — never commit it.**

## Config & models

Default model: `claude-opus-4-8` (config `model`). API key resolution: `api-key` in
config.yml, falling back to the `ANTHROPIC_API_KEY` env var of the server process.
Chat uses short `max-tokens`; the bot loop uses adaptive thinking with a 16K budget.
