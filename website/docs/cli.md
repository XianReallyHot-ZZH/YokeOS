# CLI Commands

***In one sentence: the `yokeos` command line is the foundation's front door — initialize the workspace, manage agents, chat interactively, start services. Twelve subcommands cover daily operations.***

## The 12 commands

| Category | Command | Description |
|------|------|------|
| Startup & status | `yokeos init` | Initialize the `.yokeos/` workspace (idempotent, never overwrites) |
| Startup & status | `yokeos status` | Config and runtime status |
| Startup & status | `yokeos chat [--profile <name>]` | Interactive multi-turn chat, with context and tool-call history |
| Startup & status | `yokeos serve [--port 8080]` | Start the HTTP API and web console |
| Startup & status | `yokeos gateway` | Start the multi-channel daemon |
| Agent management | `yokeos profile list` | List all agents |
| Agent management | `yokeos profile create <name>` | Create an agent (minimal AGENT.md template) |
| Agent management | `yokeos profile show <name>` | Show an agent's definition |
| Agent management | `yokeos profile delete <name>` | Delete an agent directory |
| Queries | `yokeos provider list` | Configured providers |
| Queries | `yokeos tool list` | Registered tools |
| Queries | `yokeos session list` | Session history |

> The `profile` command-group name is historical; it operates on agent directories under `.yokeos/agents/` — [one directory, one agent](./agent).

## Three run modes

| Mode | Command | Notes |
|------|------|------|
| Interactive chat | `yokeos chat` | The primary mode for development and daily use; `--message "xxx"` sends one message and exits |
| HTTP API | `yokeos serve` | Serves the REST API and web console on the given port (default 8080); scheduled tasks run alongside |
| Daemon | `yokeos gateway` | Runs in the background, serving multiple channels |

All three modes share the same agent configuration and session storage — only the access layer differs.

## Startup design

Commands split in two: those that don't need a Spring context (`init`, `profile list`) operate on files directly and start fast; those that call LLMs (`chat`, `serve`, `gateway`) boot the context. Each subcommand is its own command class, and the entry module carries test coverage like every other — entry logic is where users first meet YokeOS.

## Configuration and secrets

- Sensitive configuration (API keys, MCP credentials) is injected via **environment variables**, never hardcoded: configs use `${ENV_VAR}` placeholders resolved at load time
- Configuration is validated on load (required fields, formats); missing or invalid values produce clear errors — **never silent failures**
- Encrypted storage, key rotation, and enterprise KMS/Vault integration come in the extension phase

## Phase 1 boundaries

- IM channels (WeCom, Feishu, DingTalk, Slack) come in the extension phase through the Channel Adapter plugin mechanism, all backed by the Web Service's agent APIs — agent logic is never reimplemented
- Streaming output (SSE) and async tool execution come in the extension phase
