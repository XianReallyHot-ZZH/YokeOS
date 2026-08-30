# ReAct Loop (Capability 2: the Agent's Brain)

***In one sentence: the Agent's reasoning engine, self-implemented — the LLM decides whether and which tool to call, the foundation executes and feeds the result back, the LLM decides the next step. Loop behavior stays fully controllable.***

## What it solves

A bare model only generates text; multi-step work needs an engine driving the think → act → observe loop. Handing that engine to an external framework buys convenience at the price of a black box: loop behavior can't be customized, tool execution isn't yours, and failures can't be traced. YokeOS **implements it in-house** — the core loop is a few dozen lines of Java, giving complete command of how Agents work and room to customize later. **What this enables**: agents decide when and which tools to call without hard-coded flows; multi-step tasks complete within one conversation (read a file, analyze, call an API, generate a report); complex business processes need no pre-orchestration — the agent chooses its execution path at runtime.

## How it works

### The algorithm

1. Append the user message to the session history
2. Assemble the prompt (system prompt + bootstrap + skills + long-term memory + history + available tools)
3. Call the LLM provider
4. **No** tool call in the response → return the final response
5. **Has** a tool call → execute the tool, append the result as a tool message
6. Back to step 2
7. At the iteration cap (default 10, overridable per agent) force-stop

![The ReAct loop: Reason → Act → Observe, looping until no tool call or the iteration cap](/images/docs-react-loop.svg)

### The modules

**`ReActLoop`** — the core engine. Takes a session and a user message, returns the final response; tracks iterations, calls `ProviderService` for LLM calls and `ToolExecutor` for tools, accumulating every response and tool result into the session history.

**`PromptBuilder`** — assembles each turn's prompt in a fixed order:

1. System prompt (`AGENT.md` body + bootstrap files + referenced skill bodies; suffixed with the current date and time — the LLM doesn't know today's date on its own; in scheduled scenarios "today" rests entirely on this line)
2. Memory injection (conversation history + long-term memory)
3. Conversation history (truncated by `max_history_turns`)
4. This agent's available tools, in Function Calling format

**`ToolExecutor`** — executes the LLM's tool calls: find the tool in the `ToolRegistry`, run the sandbox check, execute, wrap the result as a `ToolResult`, and write the `tool_invocations` audit record. Failures retry with exponential backoff, three attempts by default.

**`AgentService`** — the unified entry shared by all three trigger sources: stage the current profile into the request context, run the loop, persist the session, clean up.

### The protocol-adapter boundary

Vendor protocol differences (OpenAI tools, Anthropic tools, Gemini function declarations) are absorbed by Spring AI's format translation — but the framework's built-in **automatic tool execution is explicitly disabled**. Tool scheduling and execution belong entirely to `ReActLoop` + `ToolExecutor`; otherwise tools get called twice.

## Target usage

```
You: Check the recent nginx error logs; if something's wrong, push it to the team channel
    → Turn 1: LLM calls shell (tail the error log) → whitelist check → execute → feed back
    → Turn 2: LLM sees 502s, calls notify with a summary → whitelist check → execute → feed back
    → Turn 3: no more tool calls — final response returned
```

One conversation, a multi-step task, fully traced: every LLM call lands in `llm_calls`, every tool call in `tool_invocations`.

## Phase 1 boundaries

- No parallel tool calls (multiple calls in one response execute in order)
- No agent-to-agent delegation, no streaming responses (SSE)
- Context overflow uses simple truncation (keep the system prompt and the most recent N turns); summarization-based compression comes in the extension phase
