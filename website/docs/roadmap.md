# Roadmap

***The philosophy: slow is fast — restrained and focused. Make the single-node runtime kernel solid first, so that running and managing a fleet of agents on one node is genuinely usable; then grow distributed capabilities on top of it.***

## Three phases

### Phase 1 (current): single-node runtime kernel

- A complete runtime kernel aligned with the reference: [LLM routing](./provider), [self-implemented ReAct](./react-loop), [two-layer memory](./memory), [tools + sandbox](./tool-sandbox), [notify + scheduling](./notify), [REST API + web console](./web-service)
- [One directory = one agent](./agent), dynamic management, multi-agent coexistence, packaged distribution
- Audit tables and the whitelist sandbox in place from day one

Phase 1 deliberately pursues no product-level differentiation: deliver unit by unit against the proven reference with equivalent acceptance, and let the engineering process itself be the deliverable — product-level forks wait for real usage judgment. Execution follows the reference's public build sequence (lessons 16→31), **self-paced but in order**, no calendar timeboxes, each lesson judged by its demonstrable outcome — see the [sequence table in Quick Start](./quick-start#milestone-targets).

### Phase 2 (planned): capability completion & distributed foundation

- **Knowledge base & semantic memory**: document ingestion, chunking, vector retrieval — completing the capability map
- **Distributed foundation**: stateless nodes, externalized state (sessions, memory, audit, and config externalized along separate paths), multi-replica deployment for scale and availability
- **Platform baseline upgrade**: Spring Boot 4 + Spring AI 2.0 — Phase 1 locking 3.5.x is a deliberate choice to stay unit-comparable with the reference, honestly recorded as known technical debt; the upgrade doubles as a real architecture-upgrade exercise

Going from "one department's pilot" to "serving the whole company" hits three walls: load, failures, and governance at scale. The evolution principle is **stateless instances, externalized state** — already reserved in the Phase-1 architecture: instances only "receive messages, call models, run tools, and read/write externalized state."

### Phase 3 (vision): cross-node agent collaboration

- An agent communication substrate with A2A integration (v1.0 stable spec since 2026-03)
- Cross-node discovery, delegation, and reliable async coordination

### Horizontal capabilities (added across phases)

Multi-tenancy, SSO, staged approval (HITL), full audit and tool policies, observability, and web-management enhancements.

> This layer is where YokeOS differentiates from personal-grade agent OSes: the management endpoints explicitly parked for the extension phase (see the [Web Service page](./web-service) — schedule management, whitelist management) get re-decided here, together with the audit dashboard and web authentication.

## What we don't do

- **No visual workflow orchestration**: orchestration platforms (Dify et al.) can run on top of YokeOS as clients — complementary, not competing
- **Not in Phase 1**: fallback and hedge racing, SSE streaming, authentication and RBAC, container-grade sandboxing, knowledge base, multi-tenancy — each has a named home, listed in the "Phase 1 boundaries" of its capability page
- **No anchoring on concepts**: the roadmap anchors on enterprise needs that don't change (private, controlled, auditable, Java-aligned), not on category words that may be diluted or renamed

## Delivery discipline

- One atomic deliverable per lesson; lesson types route differently — code lessons run the full spec flow and produce code, review lessons only review designs, wiring lessons add no new specs and only harden end-to-end paths, demo lessons do real runs and releases
- If a lesson can't finish, its tail features move to the extension list immediately — every lesson must produce a demonstrable outcome
- The two audit tables (`tool_invocations`, `llm_calls`) start being written from the very first lesson with LLM and tool calls — never deferred with "logs are enough"

## Next steps

- [Quick Start](./quick-start) — target usage and the 16-lesson milestone list
- [Why YokeOS](./why) — the demand judgment behind this roadmap
