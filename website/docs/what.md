# What is YokeOS

***YokeOS is a Java-native Agent foundation (Agent Harness OS) installed on the enterprise's own infrastructure: one directory defines an Agent, one foundation runs a fleet of them — private deployment, full-chain auditability, no cloud lock-in.***

It runs on the enterprise's own K8s clusters, VMs, or bare metal as a unified foundation for business Agents (DevOps assistants, customer-service agents, HR assistants, sales assistants, knowledge-management agents…), with one shared set of channels, model routing, tool calling, memory, and sandboxed execution. Data never leaves the enterprise's infrastructure, and no cloud locks you in.

The vision: to be the Agent runtime foundation that strictly-regulated enterprises trust — so that banks, governments, telcos, energy companies, and hospitals, where data cannot leave the premises, behavior must be auditable, and every new component must pass existing security review, can run every business Agent on infrastructure they fully control — **managing a fleet of Agents the way an operating system manages processes**.

## Three layers: Model → Harness → Foundation

To place YokeOS, first untangle three terms:

| | Bare Model | Agent Harness | Agent Foundation (Agent Harness OS) |
| --- | --- | --- | --- |
| Scope | A single LLM call | **One** reliable agent | A **fleet** of agents |
| Provides | Text generation | Drives the reason → act → observe loop, assembles context, bounds tools, records audit | Lifecycle, channels, model routing, shared registries, scheduling, governance, admin + API |
| Analogy | A CPU instruction | A process with its runtime | An OS running many processes |

A bare model only generates text; the harness wraps it into an Agent that can reliably *do* things; the Agent foundation sits above the runtime and harness, managing multiple Agents' lifecycles, unified access, memory, and audit. In one sentence: **the runtime and harness make one Agent run, and run correctly; the Agent foundation gets a fleet of Agents managed inside an enterprise.** YokeOS is the third column — and it ships the second one for every Agent it runs.

## Six core capabilities

The Phase-1 runtime kernel consists of six core capabilities:

- **LLM Routing** — a Provider abstraction unifies mainstream models; Agents are vendor-agnostic, switch at runtime, local inference supported
- **ReAct Loop** — the Agent's reasoning engine, self-implemented, fully controllable
- **Memory** — session + long-term layers, so Agents retain state across conversations
- **Tool System** — built-in file, shell, and HTTP tools behind a three-whitelist sandbox, three extension tiers
- **Notify & Schedule** — push results to notification channels; run on cron — the third trigger source
- **Web Service** — all capabilities exposed over REST, plus a web admin console

Each capability's design is detailed under [Architecture](./architecture).

## Key features

- 🤖 **One directory = one Agent** — a directory containing `AGENT.md` defines an Agent. No code. Multiple Agents coexist on one instance.
- 📡 **Dynamic management** — CRUD over REST, one-sentence draft generation, drop a directory and it goes live. No restarts.
- 🛠️ **Skill templates** — `SKILL.md` packages reusable procedural knowledge that Agents reference on demand; compatible with the agentskills.io open format.
- ⏰ **Runs on a schedule** — per-Agent cron tasks plus notification channels, every execution traced.
- ☕ **Java native** — Java 21 + Spring Boot, one executable JAR, reusing the enterprise's existing Java ops toolchain.
- 🔒 **Private and controlled** — runs on your own K8s, VMs, or bare metal. Data stays home. No cloud lock-in.
- 🛡️ **Security isolation** — file, command, and domain whitelists; least privilege; credentials via environment variables; full-chain audit persisted from day one.
- 🌐 **Stateless by design** — instances hold no state; state is externalized. The road to distributed is built in.

## Target shape and current status

The intended YokeOS workflow: drop a written `AGENT.md` into the workspace and get a business Agent that can chat, call tools, run on schedule, and push notifications — see [Quick Start](./quick-start).

**Phase 1 is in progress**: what's delivered today is the initiation document chain (positioning, requirements, technical design, AI programming guide); the runtime kernel is being delivered unit by unit against a proven reference with equivalent acceptance — see the [Roadmap](./roadmap). These doc pages describe the target shape distilled from that chain, updated as the implementation lands.

## Relationship to OryxOS

YokeOS enters the agent-foundation category by building on a proven reference: in Phase 1 it replicates the runtime kernel of [OryxOS](https://github.com/oryx-labs/oryxos) section by section — a later entrant of the same kind, the same stack, and the same anchors. We don't hide the starting point, and we don't re-claim design firsts the category has already proven; the difference lives in the process: a fully spec-driven build with traceable specs and acceptance evidence for every unit.

## Next steps

- [Why YokeOS](./why) — the enterprise demand data and where the Java ecosystem stands
- [Quick Start](./quick-start) — target usage and the Phase-1 milestone list
- [Roadmap](./roadmap) — three phases and horizontal capabilities
