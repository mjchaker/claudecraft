# ClaudeCraft architecture

## Overview

```
Player chat / commands
        │
        ▼
┌─────────────────────────────  Spigot main thread  ─────────────────────────────┐
│  ChatListener (@claude ...)      AskCommand (/ask)      BotCommand (/bot ...)  │
│         │                              │                        │              │
│         └────────────┬─────────────────┘                        ▼              │
│                      ▼                                    BotManager           │
│                 ChatService                        (entity + task lifecycle)   │
└──────────────────────│──────────────────────────────────────────│──────────────┘
                       │  submit                                  │  submit
                       ▼                                          ▼
              ┌─ worker thread ─┐                        ┌─ worker thread ──────┐
              │  Messages API   │                        │  BotAgent loop:      │
              │  (per-player    │                        │  create → tool_use → │
              │   history)      │                        │  execute → results → │
              └────────┬────────┘                        │  ... until end_turn  │
                       │                                 └──────────┬───────────┘
                       ▼                                            │ Sync.call
                 reply to chat                                      ▼
                                                     ┌── Spigot main thread ──┐
                                                     │ BotTools: observe,     │
                                                     │ move_to, place_blocks, │
                                                     │ break_blocks, say      │
                                                     └────────────────────────┘
```

Two independent features share one Anthropic client (`LlmService`, official Java SDK):

1. **Chat bridge** — `ChatService` keeps a bounded per-player history (`Deque<Turn>`) and
   sends it with a configurable system prompt. Replies are delivered on the main thread,
   to the asker or broadcast (config `chat.broadcast`).

2. **Builder bot** — `BotManager` owns a single named, invulnerable, AI-disabled living
   entity (config `bot.entity-type`) and at most one running `BotAgent` task.

## The agent loop (`BotAgent`)

Standard Messages API tool-use loop, one task per `/bot task`:

1. Seed message: the requesting player + task text, instruction to observe first.
2. `messages.create` with the 5 tool definitions, adaptive thinking, config'd system prompt.
3. For each content block in the response:
   - `text` → broadcast as bot narration (config `bot.narrate`)
   - `thinking` / `redacted_thinking` → echoed back verbatim in the assistant turn
     (required by the API for thinking + tool use)
   - `tool_use` → executed via `BotTools`, result queued as a `tool_result` block
4. Append assistant echo + tool results; repeat until `stop_reason != tool_use`,
   cancellation (`/bot stop`), or the iteration cap (`bot.max-iterations`).

Failures anywhere are caught as `Throwable`, logged, and reported in chat.

## The bot's tools ("skills")

| Tool | Input | Notes |
|---|---|---|
| `observe` | — | Position, time/weather, players ≤96 blocks, terrain heightmap (21×21 columns, run-length encoded `MATERIAL@y xN`), notable non-terrain blocks (≤150), remaining block budget |
| `move_to` | `to: "x,y,z"` | Animated 0.6-blocks/tick teleport steps; ≤128 blocks per hop |
| `place_blocks` | `blocks: ["x,y,z,MATERIAL", ...]` | Validates material, world bounds, build radius, budget; places with physics off for reliability |
| `break_blocks` | `blocks: ["x,y,z", ...]` | Same rails; bedrock excluded; can be disabled (`bot.allow-breaking`) |
| `say` | `message` | Broadcasts as `<BotName>` |

Design choices:

- **String-encoded coordinates** (`"x,y,z,MATERIAL"`) keep schemas trivial, parsing robust,
  and token usage low compared to nested object arrays.
- **Run-length-encoded heightmap** gives the model a compact terrain picture (~1–2K tokens)
  instead of a raw block dump.
- **Absolute world coordinates everywhere** — the system prompt teaches the convention once
  (`surfaceY + 1` to stand on ground) and every tool uses it consistently.

## Threading model

- The Bukkit API is not thread-safe: every world read/write goes through
  `Sync.call(plugin, callable)` → `callSyncMethod` with a 15s timeout.
- LLM requests never run on the main thread; the server tick loop is unaffected by API
  latency.
- `move_to` bridges the two worlds with a `CompletableFuture` completed by a per-tick
  `BukkitRunnable`.

## Safety rails

All enforced in `BotTools`/`BotManager`, configured in `config.yml`:

- `bot.max-blocks-per-task` — combined place+break budget, reset per task
- `bot.max-build-radius` — sphere around the bot entity for any world edit
- `bot.max-iterations` — hard cap on agent loop turns
- `bot.allow-breaking` — disable `break_blocks` entirely
- Permissions: `claudecraft.ask` (default all), `claudecraft.bot` (default op)

## Shading

The Anthropic Java SDK and its dependency tree are bundled into the plugin jar.
Java-only deps (Jackson, OkHttp, Okio, slf4j, httpclient5, victools, swagger,
standardwebhooks) are relocated under `dev.claudecraft.libs.*` to avoid conflicts with
other plugins. `com.anthropic` and `kotlin` are **not** relocated — kotlin-reflect
metadata embeds original class names and breaks under relocation (see CLAUDE.md).
