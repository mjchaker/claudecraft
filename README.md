# ClaudeCraft

A Spigot plugin that connects your Minecraft server to Claude (Anthropic API):

- **In-game chat with Claude** — `/ask <question>` (aliases `/claude`, `/ai`), or just type
  `@claude <question>` in chat. Each player gets their own rolling conversation history.
- **An AI bot you can deploy into the world** — `/bot spawn` places a named bot entity next to
  you; `/bot task build a small oak cabin near me` starts an agentic loop in which Claude
  *observes* the terrain around the bot, plans, moves, places/breaks blocks, and narrates its
  progress in chat until the job is done.

## How the bot "sees" and acts

The bot runs a tool-use loop against the Anthropic Messages API. Its skills (tools):

| Tool | What it does |
|---|---|
| `observe` | Position, time/weather, nearby players, a run-length-encoded terrain heightmap, and a list of notable non-terrain blocks (existing builds) |
| `move_to` | Walks/floats the bot to a coordinate (animated, tick-by-tick) |
| `place_blocks` | Places a batch of blocks (`"x,y,z,MATERIAL"`), budget- and radius-limited |
| `break_blocks` | Clears blocks (never bedrock; can be disabled in config) |
| `say` | Talks in server chat |

All world access is marshalled onto the main server thread; the LLM loop itself runs on a
worker thread so the server never blocks on the API.

Safety rails (see `config.yml`): max blocks per task, max build radius, max agent iterations,
and a toggle for block breaking.

## Building

Requires JDK 17+ and Maven:

```sh
cd claudecraft
mvn package
```

The shaded plugin jar is produced at `target/ClaudeCraft-1.0.0.jar`. The Anthropic Java SDK
(and its OkHttp/Jackson dependencies) are relocated under `dev.claudecraft.libs.*` so they
cannot conflict with other plugins.

## Installing

1. Drop `ClaudeCraft-1.0.0.jar` into your server's `plugins/` folder (Spigot/Paper 1.20.x, Java 17+).
2. Provide an API key either by:
   - setting `api-key` in `plugins/ClaudeCraft/config.yml`, or
   - exporting `ANTHROPIC_API_KEY` in the environment of the server process.
3. Restart the server.

## Commands & permissions

| Command | Permission (default) | Description |
|---|---|---|
| `/ask <msg>` (or `@claude <msg>` in chat) | `claudecraft.ask` (everyone) | Chat with Claude |
| `/bot spawn` / `despawn` | `claudecraft.bot` (op) | Deploy / remove the bot |
| `/bot task <description>` | `claudecraft.bot` (op) | Give the bot a job |
| `/bot stop` | `claudecraft.bot` (op) | Cancel the current job |
| `/bot status`, `/bot say <msg>` | `claudecraft.bot` (op) | Inspect / talk |

## Configuration highlights (`config.yml`)

- `model` — defaults to `claude-opus-4-8`.
- `chat.trigger`, `chat.broadcast`, `chat.history-limit`, `chat.system-prompt`.
- `bot.entity-type` — any living entity (default `VILLAGER`). A true player-skin NPC requires
  NMS packets or Citizens/ProtocolLib, which the pure Spigot API doesn't expose; the bot is a
  named, invulnerable, AI-disabled entity instead.
- `bot.max-blocks-per-task`, `bot.max-build-radius`, `bot.max-iterations`, `bot.allow-breaking`,
  `bot.system-prompt`.

## Development & testing

- Design details: [docs/architecture.md](docs/architecture.md). Contributor/agent notes
  (build constraints, threading rules, shading pitfalls): [CLAUDE.md](CLAUDE.md).
- End-to-end tests live in [test-client/](test-client/) (mineflayer headless client):
  `test.js` exercises the command/chat wiring; `live-test.js` runs a full build task and
  independently verifies the placed blocks. Both expect a local Paper 1.20.4 server in
  `test-server/` (gitignored — it holds your API key and world data).

## Notes

- Chat requests use adaptive thinking and short token limits; bot tasks use adaptive thinking
  with a larger budget and echo thinking blocks back across tool turns, as required by the API.
- The plugin talks directly to `api.anthropic.com` via the official Anthropic Java SDK.
- Each `/bot task` resets the block budget.
