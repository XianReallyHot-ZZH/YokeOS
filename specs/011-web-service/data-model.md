# Data Model: Web Service 与管理台第一版（第26节）

Phase 1 产物。**本节无新表、无表结构变更**（宪法 7：手工脚本不动，`ddl-auto=none` 维持）——既有 `sessions` 表三列本节启用读写；新增一个 core 值对象与一组 Web DTO。

## 表：`sessions`（既有，18 节建表；本节启用三列）

| 列 | 类型 | 本节用法 |
|----|------|---------|
| `session_id` | VARCHAR(255) PK | 路径参数 `{id}`；三元组拼接单点在 `SessionIds`（H4④），Web 只传三元组不拼串 |
| `agent_name` | VARCHAR(128) NOT NULL | 摘要视图字段（storage 实体映射处注明 ↔ profileName，18 节拍板②） |
| `channel` | VARCHAR(64) NOT NULL | 本节新增取值：`web`（会话保持）、`invoke`（一次性调用）；`cli`/`scheduler` 既有 |
| `user_id` | VARCHAR(128) NOT NULL | 创建请求可选传入，缺省 `default`；**invoke 每次唯一**（无状态落法，research D3） |
| `messages_json` | TEXT | 查历史端点读取（最多最近 100 条）；列表端点**不读**（摘要不带消息体） |
| `status` | VARCHAR(16) NOT NULL | `active`/`archived`；归档 = 置 archived（标记不终结，research D4）；列表 `?status=` 过滤 |
| `created_at` | TIMESTAMP NOT NULL | 摘要视图可含 |
| `last_active_at` | TIMESTAMP | `listRecent` 排序键（倒序）；save 时已维护 |
| `archived_at` | TIMESTAMP | `archive` 写入 |

生命周期规则（research D4）：`active → archived` 经 DELETE 端点（幂等方向单向；无 unarchive 端点，扩展位）；archived 会话仍可 getOrCreate 幂等返回、可发消息（状态不变）——**归档是标记不是终结**。

## 值对象：`SessionSummary`（core，session 包，新）

列表视图的元数据投影——不携带消息体（列表端点不反序列化 `messages_json`）。

| 字段 | 类型 | 说明 |
|------|------|------|
| `sessionId` | String | 会话标识 |
| `agentName` | String | Agent 名（= Profile name） |
| `channel` | String | `cli` / `web` / `invoke` / `scheduler` |
| `userId` | String | 用户标识 |
| `status` | String | `active` / `archived` |
| `lastActiveAt` | LocalDateTime（可空） | 最近活跃时间 |

约束：`listRecent(int limit)` 返回按 `last_active_at` 倒序、至多 limit 条；limit 上限由调用方（Controller）钳制为 100。

## Web DTO（yokeos-web `controller/dto`，record，投影字段）

| DTO | 字段 | 方向 |
|-----|------|------|
| `CreateSessionRequest` | `profile`（必填）、`userId`（可选） | 请求 |
| `MessageRequest` | `content`（必填，≤32KB） | 请求 |
| `MessageResponse` | `reply` | 响应 |
| `SessionView` | `sessionId`、`profileName`、`messages`（≤100 条，按发生序） | 响应 |
| `SessionSummaryView` | = `SessionSummary` 字段投影 | 响应 |
| `ProfileView` | `name`、`description`、`providerName`、`model`、`tools` | 响应 |
| `ToolView` | `name`、`description` | 响应 |
| `InfoView` | `product`、`version`、`providers`（去重排序） | 响应 |

校验规则：`profile` 空 → 400；`content` 空或 >32×1024 字符 → 400；DTO 内不出现审计字段与内部路径（门面分寸）。

## 前端数据契约（管理台消费，只读）

五页各绑一个 GET 端点，消费统一信封的 `data`：会话页 ← `SessionSummaryView[]`；Agent（Profile）页 ← `ProfileView[]`；Tool 页 ← `ToolView[]`；长期记忆页 ← `String`（全文，按两分区标题渲染）；系统状态页 ← `InfoView` + health。三态（加载中/空数据/错误）以信封 `code != 200` 为错误判据，`message` 直接展示。

## 不变量（测试钉死点）

1. 会话三元组拼接仍只在 `SessionIds` 一处——Web/invoke 入口零拼接（H4④）。
2. 归档后同三元组 `getOrCreate` 返回同一 `sessionId` 且历史保留（research D4）。
3. 两次 invoke 的 `sessionId` 不同（research D3）。
4. 列表端点不触发 `messages_json` 反序列化（摘要不带消息体——性能与口径双约束）。
