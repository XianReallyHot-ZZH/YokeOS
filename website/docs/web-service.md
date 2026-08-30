# Web Service (Capability 6: REST API + Admin Console)

***In one sentence: the Web Service is YokeOS's full external face — business systems make one HTTP call and put an Agent to work, without knowing anything about the internals. It is the only channel through which enterprises embed AI capability into existing systems.***

## What it solves

Without it, YokeOS is just a CLI tool that can't integrate with enterprise systems. With it: alerting systems call the ops agent over HTTP for event-driven triage; customer-service platforms route conversations to a service agent; any language that can send an HTTP request integrates — Java, Python, Go, frontends — with no SDK required in Phase 1.

## The 18 core endpoints

All under `/api/v1`, in five groups:

| Group | Endpoint | Description |
|------|------|------|
| Sessions | `POST /sessions` | Create a session |
| Sessions | `POST /sessions/{id}/messages` | Send a message (triggers the ReAct loop) |
| Sessions | `GET /sessions/{id}` | Session history |
| Sessions | `DELETE /sessions/{id}` | Archive a session |
| Agents | `POST /agents/generate` | One sentence → draft definition (not persisted, not registered; human-in-the-loop preview) |
| Agents | `POST /agents` | Create an agent (persist + register, no restart) |
| Agents | `GET /agents`, `GET /agents/{name}` | List / inspect definitions |
| Agents | `PUT /agents/{name}` | Update a definition |
| Agents | `DELETE /agents/{name}` | Delete (archived, not hard-deleted) |
| Agents | `POST /agents/{name}/invoke` | Stateless invocation |
| Workspace | `GET /workspace/tree` | Workspace directory tree |
| Workspace | `GET /workspace/file` | Read-only workspace file view |
| Info | `GET /profiles` | List runtime profiles |
| Info | `GET /memory` | Long-term memory |
| Info | `GET /tools` | Available tools |
| System | `GET /health` | Health check |
| System | `GET /info` | Runtime info + provider status |

One response envelope for success and errors alike: `{ "code", "message", "data", "timestamp" }`; standard HTTP status codes plus internal codes (400 bad request, 404 not found, 500 internal, 503 provider failure).

## The Phase-1 web console

A Vue 3 single-page application hosted in the same process and port as the REST API — an operations window that never touches a command line:

- **Five read-only pages**: sessions, agents (profiles), tools, long-term memory, and system status (including per-provider connectivity) — all data from read-only endpoints, no write affordances in the UI
- **Agent management**: list + view/edit/delete + the "one-sentence create → preview & edit → create" flow (the same API group as REST)
- **Workspace page**: directory tree + read-only file browsing (path-traversal-guarded server-side)

The console stops at "see and manage agents" and calls the same REST endpoints — no independent backend logic.

## Four integration patterns

| Pattern | Approach | Fits |
|------|------|------|
| Synchronous call | `POST /agents/{name}/invoke`, wait for the return | Stateless short tasks |
| Session continuity | Create a session once, send messages repeatedly | Ongoing conversations |
| Webhook trigger | Alerting systems and CI/CD call agents via webhook | Event-driven |
| Cross-language | Any language that can send HTTP | Universal integration |

## Phase 1 boundaries

- **Not in Phase 1**: authentication (no auth, internal network assumed), SSE streaming, WebSocket, RBAC, rate limiting — all deferred to the extension phase
- Schedule-management endpoints (task queries / execution history / run-now / enable-disable) and whitelist-management endpoints are **explicitly parked for the extension phase** (see ADR 0008): Phase 1's "queryable" rests on the `scheduled_tasks`/`task_executions` tables plus session queries and the audit tables; whitelists are config-file managed
- Request limits: 32KB max per message, the 100 most recent messages per session history, a 60-second cap on agent calls
