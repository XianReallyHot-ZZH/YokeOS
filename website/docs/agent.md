# One Directory, One Agent

***An Agent's definition is a directory: one `AGENT.md` under `.yokeos/agents/<name>/` — the frontmatter is this Agent's runtime configuration, the body is its task instructions. One directory is one complete, working business Agent — not something you write code to produce.***

## What it solves

Traditionally, shipping a business Agent means writing backend code: receiving messages, calling models, managing context, wiring tools, handling retries. In YokeOS all of that is supplied by the foundation; the business side describes **what to do** (the body) and **how to run** (the frontmatter), drops in a directory, and gets a working business Agent. Multiple Agents coexist on one instance, each with its own config, tools, and memory — the minimal form of "OS" in Phase 1.

## The shape of AGENT.md

```markdown
---
name: ops-agent
description: DevOps assistant
identity:
  agent_name: ops-agent
  prompt: You are a professional DevOps assistant...
provider:
  name: deepseek          # key into the explicit provider map
  model: deepseek-chat
  temperature: 0.7
  api_key: ${DEEPSEEK_API_KEY}   # from the environment, never in plaintext
tools:
  - read_file
  - shell
  - http_get
  - save_memory
  - recall_memory
  - notify
skills:
  - runbook-format        # by-name reference into the shared skill library
mcp_servers:
  - github-mcp
notify:
  channels:
    - name: team-im
      type: webhook
      config: {}
schedules:
  - cron: "0 0 8 * * ?"   # runs on its own — the third trigger source
bootstrap:
  - AGENTS.md
  - SOUL.md
  - USER.md
settings:
  max_iterations: 10
  max_history_turns: 20
---

You are a professional DevOps assistant. When triggered, ... (task instructions,
injected as the system prompt)
```

What each frontmatter field does:

| Field | Purpose |
|------|------|
| `identity` | Agent display name and persona (system prompt) |
| `provider` | Which provider and model to bind; API keys via `${ENV_VAR}` placeholders |
| `tools` | Available tool names; the foundation filters the tool subset accordingly |
| `skills` | By-name references into the shared skill library (`.yokeos/skills/<name>/`); bodies injected into the system prompt |
| `mcp_servers` | Referenced MCP servers |
| `notify` | Notification channels (Webhook adapter in Phase 1) |
| `schedules` | Cron rules (the third trigger source) |
| `bootstrap` | Bootstrap files (AGENTS.md / SOUL.md / USER.md) |
| `settings` | `max_iterations` (default 10), `max_history_turns` (default 20) |

## How it works: derived profiles and runtime registration

Everything in the foundation consumes a `Profile`. `AgentLoader.deriveProfile(agentDir)` derives one from the `AGENT.md` frontmatter and registers it in the `ProfileRegistry`; agents with `schedules` are handed to the `AgentScheduler`. Startup scanning and runtime additions run through **the same registration code** — drop a directory in and it goes live; remove it and it goes offline. No restarts.

When assembling each prompt, `ContextLoader` freshly loads three segments into the system prompt (no caching — edits take effect immediately): bootstrap files (AGENTS.md → SOUL.md → USER.md, fixed order), referenced skill bodies, and the `AGENT.md` body. Side resources (`scripts/`, `REFERENCE.md`) are not preloaded — they're fetched on demand through the foundation's existing tools (`read_file` / `shell`).

![Two intake paths, one registration routine: API creation and manual drop-in converge on the same registration entry point](/images/docs-agent-lifecycle.svg)

## Dynamic management: three equivalent entrances

Agent CRUD is restart-free across the board, via three equivalent entrances:

1. **REST API**: generate a draft from one sentence (`POST /api/v1/agents/generate`, returned as a preview — **not persisted, not registered**); a human reviews it (especially schedules and tool permissions), then `POST /api/v1/agents` persists and registers it
2. **Web console**: the Agent management page drives the same API group, covering "one-sentence create → preview & edit → create → edit → delete"; a workspace page browses the directory tree read-only
3. **Drop the directory**: put a finished Agent directory into `.yokeos/agents/`; the foundation watches for changes, validates, and loads it — plug and play

`.yokeos/agents/` is the single source of truth; generation is audited, and an invalid LLM-drafted definition returns a clear validation error — never a silent failure.

## The workspace

Agent directories live inside a `.yokeos/` workspace (created by `yokeos init`, idempotent, never overwrites):

```text
.yokeos/
├── agents/            # each subdirectory = one Agent
├── skills/            # shared skill library (one SKILL.md per subdirectory)
├── output/            # agent artifacts
├── memory/
│   └── MEMORY.md      # long-term memory
├── sessions/          # session export (source of truth is SQLite)
├── logs/              # structured logs
├── AGENTS.md          # bootstrap: project-level agent behavior
├── SOUL.md            # bootstrap: default agent persona
├── USER.md            # bootstrap: user preferences (read-only to agents)
├── mcp_servers.yaml   # MCP configuration
└── yokeos.db          # SQLite
```

## Skills: reusable capability templates

Reusable procedural knowledge lives in the shared skill library: `.yokeos/skills/<name>/`, one `SKILL.md` per subdirectory, compatible with the agentskills.io open standard. An Agent references skills by name in its frontmatter (`skills: [name]`); the foundation injects the referenced bodies into the system prompt to tightly constrain output. The boundary is crisp: **a Skill is not a Tool** — it never enters the tool registry; loading and injection belong to the context layer.

## Target usage

The two daily demos in [Quick Start](./quick-start) each show a different richness of definition: the **daily weather** agent is a bare `AGENT.md`; the **daily tech digest** agent references a shared formatting skill by name and adds MCP servers.

## Phase 1 boundaries

- One-sentence drafts are previews — no draft approval flows, no agent versioning, no cross-instance sync
- Skill bodies are injected whole; progressive disclosure (metadata first, body on demand) comes in the extension phase
- An agent with scripts inherits the trust of its author — whitelisting an interpreter grants code execution; container-grade isolation comes later
