# Architecture Overview

***YokeOS is a Spring Boot monolith: two human-facing entrances plus one automated entrance, converging on a single engine; the engine orchestrates three capability blocks; below them sits one storage layer. Everything converges into one process — "one binary, install and run."***

![YokeOS architecture: access layer → engine layer → capability layer → foundation layer, three trigger sources converging on one AgentService](/images/docs-architecture.svg)

## Four layers

Top to bottom:

1. **Access** (CLI Channel, the Web Service REST API, `AgentScheduler` cron triggers) — messages in and out
2. **Engine** (`ReActLoop`, `PromptBuilder`, `ToolExecutor`) — the Agent's brain
3. **Capabilities** (Provider, Memory, Tool) — LLM calls, context, and execution for the engine
4. **Foundation** (agent/skill/bootstrap loading, session storage, SQLite, config and secrets) — the engineering substrate

## Three trigger sources: human-pushed and clock-pushed

CLI and the Web Service are "human-pushed" — someone initiates a call. The `AgentScheduler` is "clock-pushed" — it generates and sends a message automatically when a cron expression comes due. Messages from all three entrances converge on the same `AgentService`, the unified entry point that doesn't care where a message came from: one execution path, consistent behavior, a uniform audit trail.

## How the six capabilities relate

The six capabilities are not parallel feature modules — they have explicit working relationships:

- The **ReAct loop** (capability 2) is the engine that drives "user message → LLM thinking → tool execution → result feedback → continue"
- **Provider** (capability 1) supplies LLM calls — invoked once per reasoning turn
- **Memory** (capability 3) supplies context — injected into every prompt assembly
- **Tool** (capability 4) supplies execution — the LLM decides which tool to call; the engine executes it
- **Notify & schedule** (capability 5) are two symmetric pieces: Notify is the **outbound channel** (inbound, Channels solve "how do messages get in"; Notify solves "where do results go"); the scheduler is the **third trigger path**
- The **Web service** (capability 6) is the outbound face of the internal capabilities, wrapping them as REST APIs for business systems

> **In one sentence**: Provider, Memory, and Tool feed the ReAct engine; notify and scheduling add the outbound channel and the third trigger source; the engine's capabilities reach the outside through CLI, Web Service, and cron.

## Modules

YokeOS is a Maven multi-module project with nine modules (boundaries aligned with the reference implementation):

| Module | Responsibility |
|------|------|
| `yokeos-core` | Core abstractions: `YokeTool`, `Session`, `Profile`, `AgentLoader`, `ContextLoader`, `ReActLoop`, `PromptBuilder`, `ToolExecutor`, `AgentService`, `AgentScheduler` |
| `yokeos-provider` | Capability 1: `ProviderService`, Function Calling adapter, explicit provider-name mapping |
| `yokeos-memory` | Capability 3: `MemoryService` facade, `LongTermMemoryStore` backends, `MemoryTools` |
| `yokeos-tool` | Capability 4: built-in tools, MCP client, `ToolRegistry`, `Sandbox` interface + whitelist implementation, notify adapters |
| `yokeos-channel-cli` | CLI Channel: the `yokeos chat` implementation |
| `yokeos-web` | Capability 6: REST controllers, web console hosting, unified exception handling, OpenAPI |
| `yokeos-storage` | Persistence: SQLite, session/audit/schedule repositories |
| `yokeos-cli` | CLI entry: Picocli main, 12 subcommands, `ConfigLoader` |
| `yokeos-boot` | Spring Boot startup module: main class, auto-configuration, dependency aggregation |

Modules decouple through interfaces: cross-module contracts live in `yokeos-core` and are implemented downstream (dependency inversion); circular dependencies are forbidden. Adding a new Channel or Tool in the extension phase means adding a module — `yokeos-core` stays untouched.

## Two architectural points

1. **Convergence**: all capabilities converge into one engine, one storage, one process. External dependencies (LLM APIs, external MCP servers) stay outside the application boundary — YokeOS binds to none of them.
2. **Decoupling**: engine↔capability and capability↔external boundaries are abstract interfaces, so the extension phase adds new Channels, Providers, and Tools at the edges without touching the core engine.

## Key decisions at a glance

| Decision | Choice |
|------|------|
| ReAct loop implementation | Self-implemented; no framework agent abstractions |
| Framework usage boundary | Protocol translation + `@Tool` schema generation only; auto tool execution disabled |
| Provider abstraction | Thin `ProviderService` wrapper + explicit name → ChatModel map |
| Execution model | Synchronous blocking + Java 21 virtual threads |
| Sandbox strategy | Interface first: `Sandbox` abstraction + `WhitelistSandbox` implementation |
| Persistence | SQLite + Spring Data JPA + the `MEMORY.md` file |

## Next steps

Layer-by-layer design: [Provider Routing](./provider) · [ReAct Loop](./react-loop) · [Memory](./memory) · [Tool System & Sandbox](./tool-sandbox) · [Notify & Schedule](./notify) · [Web Service](./web-service)
