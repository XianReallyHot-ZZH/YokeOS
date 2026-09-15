# Data Model: Notify——结果主动送出去的统一出口（第19节）

本节**零新表、零 schema 变更**（宪法 7：审计走 `tool_invocations` 既有路径）。涉及实体三件——一件既有、两件新增，另有一处既有实体的派生规则变更。

## 实体

### `Profile.NotifyChannelConfig`（既有，16 节）

Agent 声明的一条通知渠道，`AGENT.md` frontmatter `notify.channels` 列表项派生而来。

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | `String` | 渠道名（Agent 内唯一语义；`notify` 的 `channel` 参数按它匹配，拍板①） |
| `type` | `String` | 渠道类型；第一阶段支持集 = `{"webhook"}`（缺省视为 `webhook`，不支持值派生期剔除，拍板②） |
| `config` | `Map<String, String>` | 渠道配置（webhook 档为 `url` 等；凭证值走 `${ENV_VAR}` 占位解析） |

**派生规则变更（本节）**：`type` 从硬编码 `"webhook"` 改为三态读取——显式支持值照收 / 缺省补 `"webhook"` / 不支持值记错误日志剔除条目（Agent 与其余渠道不受影响）。

### `NotifyTarget`（新增，yokeos-tool）

一次推送的目标，由渠道适配实现自行解释。

| 字段 | 类型 | 说明 |
|------|------|------|
| `channelType` | `String` | 渠道类型（与 `NotifyChannelConfig.type` 同源），路由到对应适配实现 |
| `config` | `Map<String, String>` | 目标配置（defensive copy），如 webhook 档的 `url` |

**校验**：由实现承担（`WebhookNotifyAdapter` 要求 `url` 非空，缺失抛 `IllegalArgumentException` 点名）；接口层不做结构性校验（接口语汇零渠道特有词）。

### `notify` 工具调用记录（既有 `tool_invocations` 行）

每次 `notify` 调用（含发送失败）经 `ToolExecutor` 既有路径写入一行：`tool_name="notify"`、`arguments_json` 为入参原文、成败与失败原因照既有列语义。**零新增列、零新增写入逻辑**。

## 关系

```text
AGENT.md frontmatter ──AgentLoader.deriveProfile──▶ Profile.NotifyChannelConfig（0..n 条）
                                                            │ notify 执行期按 name 解析
                                                            ▼
ProfileContext.current() ──▶ NotifyTools.execute ──▶ NotifyTarget(type, config)
                                                            │ Map<type, Adapter> 路由
                                                            ▼
                                                  NotifyChannelAdapter.send(target, content)
                                                            │ tool_invocations 既有审计行
ToolCallRequest ──ToolExecutor──▶ ToolResult（成功 "已推送" / 失败点名）◀──┘
```

## 状态与生命周期

- 渠道配置：随 Profile 派生只读（frontmatter 改动重启生效，运行时注册归 29 节）。
- `NotifyTarget`：每次调用即时构造，无持久化。
- 推送动作：一次性、不可重试（research D6——重复推群风险）；成败只经审计留痕，无补偿/重试队列（扩展阶段）。

## 数据量假设

每 Agent 通知渠道个位数；推送频率低（人工对话触发，定时批量归 25 节）；`tool_invocations` 增量与普通 Tool 调用同量级。
