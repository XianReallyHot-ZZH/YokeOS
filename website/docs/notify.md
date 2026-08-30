# Notify & Schedule (Capability 5: Reaching Humans, Running Unattended)

***In one sentence: Notify is the outbound channel — agents deliver results where people will see them; scheduled tasks are the third trigger source — agents run on time with nobody pressing the button. Both reuse the same execution path as human pushes.***

## What it solves

CLI and the Web Service are conversational — someone sends a message in and waits for the reply. Daily digests, inspections, and summaries have no one waiting: the `AgentScheduler` fires on schedule, and unless the agent can **push** its result to where people actually look (the enterprise IM group), the run sinks without a trace. **What this enables**: reports, inspections, and digests run on their own without a human standing by; results reach people proactively via group-bot webhooks; every scheduled execution is persisted and queryable — whether it ran, what it did, all on record.

## How it works

### Notify: the outbound channel

The `NotifyChannelAdapter` interface expresses "deliver this content to that notification target" — modeled separately from inbound channels because the semantic direction is opposite. Phase 1's only implementation is the `WebhookNotifyAdapter`: one generic HTTP webhook covers every scenario — WeCom, Feishu, DingTalk, and Slack group bots all expose webhook URLs, so no per-vendor SDK integration is needed.

An agent declares `notify.channels` in its frontmatter (each with `name`, `type: webhook`, `config`) and calls the built-in `notify(content, channel)` tool with just the content — never the webhook address, which is runtime configuration, not conversation material. Before sending, the push passes the domain whitelist (shared with `http_post`); the push is written to the audit tables.

![NotifyTools design: interface first, with WebhookNotifyAdapter as Phase 1's only implementation](/images/docs-notify.svg)

### Scheduled tasks: the third trigger source

Each agent declares cron rules, time zones, and the message to send in its frontmatter's `schedules` field. The `AgentScheduler` **dynamically registers** tasks on Spring's `ThreadPoolTaskScheduler` + `CronTrigger` — not static `@Scheduled` annotations, because trigger rules are derived from agent definitions and can't be frozen at compile time.

![Scheduling as the third trigger source: CLI/Web Service (human-pushed) and AgentScheduler (clock-pushed) both call the same AgentService](/images/docs-scheduler.svg)

- **One path**: a clock-pushed message calls the same `AgentService` entrance; the `ReActLoop` never knows this run was scheduled. The same agent can be triggered manually for verification (`yokeos chat` or `POST /agents/{name}/invoke`)
- **Concurrency control**: one in-process lock per task — if the previous run hasn't finished, the next trigger point is skipped; no queuing, no double runs
- **Session identity**: scheduled runs get sessions too — the `session_id` formula stays the same with channel and user fixed to `scheduler`; consecutive scheduled runs share one session, and no new concepts are invented for scheduling
- **Persisted state**: two tables — `scheduled_tasks` (registrations and run status) and `task_executions` (history of every run, success or failure) — survive restarts; definitions still come from the frontmatter, re-reconciled from files on boot

## Target usage

```yaml
# AGENT.md frontmatter
schedules:
  - cron: "0 0 8 * * ?"     # every morning at 8
notify:
  channels:
    - name: team-im
      type: webhook
      config: {}              # webhook URL etc.
```

On schedule: the `AgentScheduler` fires → the agent runs its full ReAct loop → `notify(content="...")` pushes to the group bot → everything audited and queryable. That's the skeleton of the [daily weather demo](./quick-start#demo-1-daily-weather-a-bare-agent-md).

## Phase 1 boundaries

- Only the Webhook adapter ships; email and native IM SDK adapters come in the extension phase
- The scheduler's **runtime-control** endpoints (task status, execution history, run-now, enable/disable) are explicitly deferred — Phase 1's "queryable" rests on the two tables plus session queries and the audit tables
- The per-task lock covers one process only, not distributed coordination; multi-instance coordination arrives with the distributed foundation phase
