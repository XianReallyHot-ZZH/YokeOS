# Quick Start

> **Phase 1 in progress — updated as the implementation lands.** This page describes the **target usage** distilled from the document chain; it goes live together with the packaged runtime at the end of the Phase-1 sequence. Usable today: the full [initiation document chain](https://github.com/XianReallyHot-ZZH/YokeOS/tree/master/docs) and the acceptance evidence accumulating per unit.

## Target usage: a business Agent in five minutes

The design goal: initialize a workspace, write one `AGENT.md`, start chatting. Once Phase 1 completes, the full loop looks like this:

```bash
# 1 · Initialize the workspace (idempotent: never overwrites what exists)
yokeos init

# 2 · Create an Agent directory (generates a minimal AGENT.md template)
yokeos profile create ops-agent

# 3 · Edit AGENT.md: configure provider, tools, notifications, schedules;
#     write the task instructions in the body

# 4 · Chat with it
yokeos chat --profile ops-agent

# Or expose it over HTTP
yokeos serve --port 8080        # REST API + web console
```

**Prerequisites**: Java 21 and an LLM API key (DeepSeek / Qwen / Kimi / Ollama, etc.).

The initialized workspace:

```text
.yokeos/
├── agents/            # each subdirectory = one Agent
├── skills/            # shared skill library
├── output/            # agent artifacts
├── memory/
│   └── MEMORY.md      # long-term memory
├── sessions/          # session export (source of truth is SQLite)
├── logs/              # structured logs
├── AGENTS.md          # bootstrap: project-level agent behavior
├── SOUL.md            # bootstrap: default agent persona
├── USER.md            # bootstrap: user preferences
├── mcp_servers.yaml   # MCP configuration
└── yokeos.db          # SQLite
```

The three bootstrap files are loaded into the system prompt at agent startup: project context (AGENTS.md), agent persona (SOUL.md), user preferences (USER.md).

## Two daily demos: the Phase-1 acceptance

Early designs split acceptance into five demos, one per capability — but real scenarios never run capabilities in isolation. A compelling Agent stacks several capabilities together and runs on its own schedule. Phase 1's acceptance is therefore two end-to-end demos that run **every day without a human**, together covering all six core capabilities plus scheduling as the third trigger source:

### Demo 1: daily weather (a bare AGENT.md)

Every morning at 8:00 the agent checks the weather, drafts outfit advice, and pushes it to an enterprise IM group bot via webhook:

1. The `AgentScheduler` triggers on the cron declared in `schedules` — the **same execution path** as a manual trigger
2. The LLM decides to call `http_get` for the weather — domain-whitelisted, audited
3. Seeing the weather, the LLM drafts advice and calls `notify(channel="team-im")` — whitelisted and audited again
4. The full conversation stays in the auto-triggered session, queryable via `GET /api/v1/sessions/{id}`

**Acceptance points**: no human trigger anywhere; both external calls pass whitelist checks and leave audit records.

### Demo 2: daily tech digest (AGENT.md + shared Skill + MCP)

Every morning at 9:00 the agent compiles the day's tech news and pushes it — **and the digest reflects interests the user mentioned before** (say, "focus on AI and chips"):

1. The business side creates `daily-tech-digest/AGENT.md` (referencing the shared formatting skill `digest-format` by name, with a news MCP server configured) and puts the formatting standard in `.yokeos/skills/digest-format/SKILL.md`
2. The user previously said "I care more about AI and chips"; the agent called `save_memory`, writing into `MEMORY.md`
3. At trigger time the system prompt includes the body, the memory, and the skill standard; the LLM decides which news tool to call and how to organize the digest
4. Because the memory is in context, the digest naturally leans toward AI and chip coverage

**Acceptance points**: the business side writes zero Java code; the digest reflects the preference remembered in `MEMORY.md`, proving long-term memory works across days.

Both demos are clock-pushed, but both support a manual catch-up run (`yokeos chat` or `POST /agents/{name}/invoke`) — the same agent triggered from any entrance walks the same execution path, with consistent behavior and a uniform audit trail.

## Milestone targets

Phase 1 follows the reference implementation's public build sequence (lessons 16→31), **self-paced but in order**, with no calendar timeboxes. Each lesson's completion criterion is its demonstrable outcome:

| # | Type | Capability | Demonstrable outcome |
|----|------|---------|-----------|
| 16 | Code | Provider abstraction | Configure a provider and API key; send a CLI message, get an LLM reply |
| 17 | Code | ReAct loop | Multi-step task in one conversation: think → call tool → observe → continue |
| 18 | Code | CLI entry | `yokeos chat` multi-turn sessions with context and tool-call history |
| 19 | Code | Notify | Push results to a webhook channel after a run |
| 20 | Code | Tools & MCP | All built-in tools live; external MCP server connected; whitelist enforced |
| 21 | Review | Memory design review | Review record finalized (no code) |
| 22 | Code | Memory | Agent recalls user preferences across conversations |
| 23 | Review | Sandbox design review | Whitelist sandbox design finalized (no code) |
| 24 | Code | Sandbox | Overreaching paths/commands/domains blocked; blocks auditable |
| 25 | Code | Scheduling | Agent runs on cron; execution history queryable |
| 26 | Code | Web service & console | All REST endpoints live; read-only console pages up |
| 27 | Wiring | End-to-end wiring (1) | CLI → ReAct → Tool → Notify connected end to end |
| 28 | Wiring | End-to-end wiring (2) | The chain hardened into integration tests, stably repeatable |
| 29 | Code | One directory, one agent | Drop an agent directory, get a working business Agent |
| 30 | Code | Dynamic management | One-sentence draft → preview → create → edit → delete, restart-free |
| 31 | Demo | Real runs & release | Two daily demos live in a real environment; packaged release; site accessible |

The two audit tables (`tool_invocations`, `llm_calls`) start being written from the very first lesson with LLM and tool calls — never deferred with "logs are enough".

## Next steps

- [One Directory, One Agent](./agent) — the full shape of AGENT.md
- [CLI Commands](./cli) — the 12 commands and three run modes
- [Roadmap](./roadmap) — the three-phase picture
