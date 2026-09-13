# Data Model: ReAct 循环——Agent 的大脑（第17节）

Phase 1 产物。表结构出处：技术方案 §9.2（16 节已建，本节起写入）；值对象出处：教学文档第三部分 + 拍板①②。

## 表：`tool_invocations`（审计，16 节建表、**本节起写入**）

| 列 | 类型 | 约束 / 说明 |
|----|------|------------|
| `id` | INTEGER PK AUTOINCREMENT | 主键 |
| `session_id` | TEXT NOT NULL | 关联 Session（sessionId 随调用传递） |
| `tool_name` | TEXT NOT NULL | Tool 名称 |
| `input_json` | TEXT | 调用参数（原始 argumentsJson） |
| `result_json` | TEXT | 执行结果（成功存 content；失败为 NULL——原因进 `error_message`，技 §9.2 列定义） |
| `success` | BOOLEAN NOT NULL | 是否成功 |
| `error_message` | TEXT | 错误信息（可空） |
| `duration_ms` | INTEGER NOT NULL | 执行耗时（毫秒；重试场景覆盖全部尝试总耗时） |
| `created_at` | TEXT NOT NULL | 调用时间 |

写入者 = 本节 `ToolExecutor`（经 `JpaToolInvocationAuditor`）；无新表无新列——16 节 `db/schema-001-audit.sql` 即权威建表脚本，本节零改动。校验规则：**一次工具调用请求一条最终态记录**（重试是执行内部策略，不逐尝试落多条，spec Clarifications）；成败**都**写、先落审计再还结果；未注册工具名 / 坏 JSON 同样落 `success=false` 留痕；Sandbox 拒绝 24 节复用此表。

## 值对象：`Session` / `Message`（内存态，18 节持久化）

出处：教学文档第三部分第一步（技 §13「Session 内存版」）。

| 字段 / 方法 | 类型 | 说明 |
|------|------|------|
| `Session.sessionId` | String | 构造期定死（三元组拼接公式归 18 节，本节测试自造） |
| `Session.profileName` | String | 构造期定死，`AgentService` 按它查 Profile |
| `Session.messages` | `List<Message>` | 按序累积，只经 append 三兄弟写入 |
| `Session.appendUser(String)` | void | 追加 user 消息 |
| `Session.appendAssistant(ProviderResponse)` | void | 追加 assistant 消息；text 为 null 按空串（既无文本也无工具请求的收尾边界） |
| `Session.appendToolResult(String, ToolResult)` | void | 追加 tool 消息；成功存 content、失败存错误描述 |
| `Message.role` | String | `user` / `assistant` / `tool` 三值 |
| `Message.content` | String | 文本内容（或工具结果 / 错误描述） |
| `Message.toolName` | String | 仅 tool 角色非空（三元记录） |

顺序不变量：消息严格按发生序追加，事后可完整回放（坑三落点）；先累积再判停——转满 `max_iterations` 的每轮都留痕。`SessionManager` 接口本节仅 `save(Session)`；`InMemorySessionManager` 按 sessionId 存 `ConcurrentHashMap`。

## 值对象：Provider 中性协议（core，拍板①）

| 值对象 | 字段 | 说明 |
|--------|------|------|
| `ProviderRequest` | `promptText` : String / `availableTools` : `List<YokeTool>` | 组装产物：拼好的单段文本 + 点名工具清单（不进文本） |
| `ProviderResponse` | `text` : String（可 null）/ `toolCalls` : `List<ToolCallRequest>` | 模型响应；`hasToolCalls()` 便捷判断 |
| `ToolCallRequest` | `name` : String / `argumentsJson` : String | 工具调用请求（argumentsJson 为 JSON 文本，执行器负责解析） |

不含 Usage——token 审计在 `SpringAiProviderService` 实现体内部闭环（`LlmCallAuditor` 收 `Integer×3`，research D1）。

## 值对象：`ToolResult`（core，拍板②）

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | String | 执行产物（成功时） |
| `success` | boolean | 成败 |
| `errorMessage` | String | 失败原因（成功时 null） |
| `retryable` | boolean | 可重试标记（执行器退避策略的输入） |

工厂：`ok(content)` / `error(errorMessage, retryable)`。

## Profile 消费字段（16 节已承载，本节开始消费）

出处：001 data-model Profile 表（全字段承载、按需消费原则）。

| 字段 | 本节消费方 |
|------|-----------|
| `identity.prompt` | `ContextLoader`——system prompt 第 1 段 |
| `bootstrap` : `List<String>` | `ContextLoader`——Bootstrap 相对序（缺省三项全取） |
| `settings.maxIterations`（缺省 10） | `ReActLoop`——轮数兜底 |
| `settings.maxHistoryTurns`（缺省 20） | `PromptBuilder`——历史轮界截断 |
| `tools` : `List<String>` | `PromptBuilder`——availableTools 只含点名工具 |
| `name` | `ContextLoader`——按名定位 `agents/<name>/AGENT.md`（目录名 = Agent 名，宪法 8） |
