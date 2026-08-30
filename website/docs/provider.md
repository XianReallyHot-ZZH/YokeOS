# Provider Routing (Capability 1: LLM Access)

***In one sentence: a Provider abstraction unifies mainstream LLMs — Agents never know which vendor they're talking to, switching at runtime takes zero code changes, and local inference is supported.***

## What it solves

Without this layer, every provider switch means rewriting call code and re-handling vendor protocol quirks. YokeOS folds that complexity behind one Provider interface: mainstream models (DeepSeek, Qwen, Kimi, GLM, Hunyuan, Doubao, Anthropic, OpenAI…) connect through Spring AI Alibaba's ready-made connectors, wrapped by YokeOS into uniform Providers — no wheel reinvention. **What this enables**: simple tasks on cheap models, complex tasks on strong ones, on the same foundation; connect the enterprise's own local inference (Ollama, vLLM) so data never leaves; multiple providers side by side — draft with a cheap model, synthesize with a strong one.

## How it works

**A thin wrapper — not zero layers, not a thick one.** Calling the framework's `ChatClient`/`ChatModel` directly (zero layers) is the shortest path, but cross-cutting concerns — multi-provider mapping, token accounting, provider status — scatter across callers. A thick layer (re-inventing a communication protocol) repeats what the framework already does. The middle path: `ProviderService` forwards calls, holds the map, and records audit — without touching protocols.

**Explicit mapping, never type scanning.** With multiple providers configured, the container holds multiple `ChatModel` beans of the same type; "scan all beans" cannot reliably tell deepseek from kimi. YokeOS maintains an explicit **provider name → ChatModel map**: each provider declares a unique name, `ProviderService` builds the map at startup, and agents reference providers by name.

![Provider architecture: ReAct loop → ProviderService → explicitly mapped ChatModels → LLM APIs](/images/docs-provider.svg)

**Every call is audited.** When the ReAct loop calls the LLM with a Profile and Prompt, `ProviderService` selects the underlying model, completes the call, and writes token usage and duration into the `llm_calls` table — basic cost transparency from Phase 1.

**Configuration and secrets.** API keys use `${ENV_VAR}` placeholders resolved from the environment, never written in plaintext; configuration is validated on load — missing or invalid values produce clear errors, never silent failures.

## Target usage

```yaml
# AGENT.md frontmatter — switching vendors is a one-line change
provider:
  name: deepseek          # → qwen / kimi / ollama, just this line
  model: deepseek-chat
  api_key: ${DEEPSEEK_API_KEY}
```

With multiple providers, different agents bind different providers; per-task routing within one agent is an extension-phase concern (see boundaries).

## Phase 1 boundaries

- **No fallback or hedge racing**: provider failures surface as errors to the agent; failover chains and circuit breakers come in the extension phase
- Cost transparency is basic-only (tokens, provider, model into the audit table); full cost aggregation and dashboards come later
- The one-sentence agent generator uses its own default-provider config key; when unset it does not silently fall back — the call returns a clear error instead
