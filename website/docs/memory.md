# Memory (Capability 3: Agents That Remember)

***In one sentence: two memory layers — session memory survives restarts, long-term memory survives conversations — so agents remember your preferences, project context, and past decisions without being told twice.***

## What it solves

An ordinary chatbot starts from zero every time. The core experience that separates an Agent foundation from a chatbot is an agent that knows you better the longer you use it. **What this enables**: remembering preferences across conversations ("I use Spring Boot, not Spring MVC"); resuming after interruptions mid-task; traceable decisions ("why did we pick DeepSeek over Kimi last time?"); teams sharing one user's preference memory across agents.

## How it works

### One facade, two layers

The ReAct loop sees a single `MemoryService` interface; the layers live inside:

- **Session memory**: the full conversation history, keyed by channel + user + agent, persisted to SQLite, restorable across restarts; truncated from the front when it exceeds the context window
- **Long-term memory**: a `MEMORY.md` file (default backend) that agents read and write through two built-in tools — `save_memory(content)` appends, `recall_memory(query)` searches by keyword; the whole file is injected into the system prompt at startup

![Memory architecture: the MemoryService facade unifies SessionManager and LongTermMemoryStore](/images/docs-memory-service.svg)

### Core and archival partitions

`MEMORY.md` is organized into two top-level partitions:

![MEMORY.md structure: the core partition is never truncated; truncation and recall apply only to the archival partition](/images/docs-memory-structure.svg)

- **Core memory**: the few facts that must always be present — **injected in full, never truncated**
- **Archival memory**: general facts and history, keeping the most recent content past 4,000 characters; `recall_memory` searches only the archival partition (the core partition is already fully injected)

Writing to core or archival is the agent's explicit choice via the `scope` parameter — the system never guesses. Phase 1 has no automatic extraction: the agent decides what to remember through when it calls `save_memory`.

### Three backends, delivered together

Long-term memory sits behind the `LongTermMemoryStore` interface (`append` / `load` / `recallByKeyword`); three implementations switch on one config line, `memory.backend`, and nothing above `MemoryService` changes:

| Backend | Underlying store | Best for |
|------|------|------|
| `MarkdownMemoryStore` (default) | one `MEMORY.md` file | Zero dependencies, human-readable, git-trackable; single-node tier |
| `SqliteMemoryStore` | the `memory_entries` table; truncation becomes `LIMIT`, recall becomes `LIKE` | Thousands of entries, structured queries |
| `Mem0MemoryStore` | self-hosted Mem0 (data stays in-house); extraction and semantic search delegated | When you truly need smart memory |

All three honor four contracts: no caching (fresh reads — `save_memory` is visible next turn); the core partition is never truncated; partition choice is explicit; recall is keyword search, kept simple.

### MEMORY.md vs USER.md

| File | Origin | Read/write | Purpose |
|------|------|--------|------|
| `USER.md` | Written by the user | Read-only to YokeOS | The user's "initial settings" (bootstrap file) |
| `MEMORY.md` | Written by the agent via `save_memory` | Read-write | The agent's "growth record" (long-term memory) |

## Target usage

```
You: From now on, digests should focus on AI and chips only
    → the agent calls save_memory(content="user prefers AI and chip coverage")

(The next day, the daily tech digest fires on schedule)
    → MEMORY.md is in the system prompt
    → the digest naturally leans toward AI and chip items
```

Memory actually working across days is one of the acceptance points of the [daily tech digest demo](./quick-start#two-daily-demos-the-phase-1-acceptance).

## Phase 1 boundaries

- No automatic fact extraction from conversations (partitions driven entirely by the agent's own calls)
- No in-process vector store; semantic search is delegated externally by the Mem0 tier — the knowledge base and semantic memory plan belongs to the extension phase
- No episodic memory (a third layer), no Memory Wiki (claim/evidence, contradiction detection), no compression (oversize is simply truncated)
