# Data Model: CLI——YokeOS 的命令行入口（第18节）

**Date**: 2026-09-15 | **Feature**: [spec.md](./spec.md)

## 持久化实体（yokeos-storage）

### sessions（手工 `db/schema-002-sessions.sql`，宪法 7）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| session_id | VARCHAR | PRIMARY KEY | `channel:user:agent`（拼接只在 JpaSessionManager，D3） |
| agent_name | VARCHAR | NOT NULL, 索引 idx_sessions_agent | 关联 Agent（技 §9.2 字面量，D2；core 领域字段 profileName 映射处注明） |
| channel | VARCHAR | NOT NULL | 接入渠道（cli / web / scheduler） |
| user_id | VARCHAR | NOT NULL | 用户标识（chat 取系统用户名） |
| messages_json | TEXT | | 对话历史整体 JSON 序列化（`List<Message>`） |
| status | VARCHAR | NOT NULL | `active`（本节唯一取值）/ `archived`（26 节写入） |
| created_at | TIMESTAMP | NOT NULL | 创建时间（@PrePersist） |
| last_active_at | TIMESTAMP | | 最后活跃（save 时刷新） |
| archived_at | TIMESTAMP | 可空 | 归档时间（本节恒空） |

`com.yokeos.storage.Session` JPA 实体字段与列一一对应（与 core 领域对象同名不同包，JpaSessionManager 内全限定引用实体、import core 类型——参照先例）。

### messages_json 内容格式

`List<Message>` 直接序列化：`[{"role":"user","content":"...","toolName":null}, {"role":"assistant","content":"...","toolName":null}, {"role":"tool","content":"...","toolName":"http_get"}]`——与 core `Message` record `(role, content, toolName)` 字段一一对应，回读用 `TypeReference<List<Message>>`（17 节 D3「role 用 String」在此兑现：零转换）。

### 既有表的只读复用

`tool_invocations`（16 节建、17 节写入）：`/tools` 经 `JpaToolInvocationReader` 按 `session_id` 只读查询，投影为 `ToolInvocationRecord`（toolName/inputJson/success/errorMessage/durationMs/createdAt）——不建新表、不加列。

## 领域对象改造（yokeos-core，17 节预告改造点）

- `SessionManager` 接口补全：
  - `Session getOrCreate(String channel, String userId, String profileName)`——同一三元组幂等返回同一条（含已恢复历史）；未命中落一条 `status=active` 新记录
  - `Optional<Session> get(String sessionId)`
  - `void save(Session session)`（17 节已有：序列化历史整体覆盖 + 刷 last_active_at）
- `Session` 新增恢复构造器 `Session(String sessionId, String profileName, List<Message> restored)`；既有构造器与 append 三兄弟不动。
- `InMemorySessionManager` 随动实现 getOrCreate/get（内存 Map 兜底，测试与轻量场景保留）。
- `com.yokeos.core.audit` 新增只读契约：`ToolInvocationRecord` record + `ToolInvocationReader` 接口（与既有 `ToolInvocationAuditor` 写口同包对称）。

## 状态与生命周期

- 会话：getOrCreate 未命中 → 新建（status=active、created_at）→ 对话累积（内存）→ save（messages_json 整体覆盖、last_active_at 刷新）→（26 节）DELETE 归档 status=archived + archived_at。
- 幂等兜底：主键即三元组拼接结果，并发 getOrCreate 撞主键 = 已存在，不产生第二条。
- 重启恢复：同库文件按主键重查 → messages_json 反序列化回领域 Session（恢复构造器）→ 继续对话在恢复历史上追加。

## 命令面（12 个，yokeos-cli）

| 命令 | 轻/重 | 数据访问 |
|---|---|---|
| init | 轻（16 节已有） | Files 建 `.yokeos/` 骨架（幂等不覆盖） |
| status | 轻 | Files 检查工作区/配置/库文件存在性，输出摘要 |
| chat | 重 | 起 `YokeosRuntime` 上下文 → CliChannel 交互（/context 读会话内存历史；/tools 只读查 tool_invocations） |
| serve / gateway | 重 | 启动骨架：上下文常驻 + keepAlive（REST 端点 26 节、多通道挂载扩展阶段；serve --port 透传，默认 8080） |
| profile list / show | 轻 | Files 读 `.yokeos/agents/` 目录清单 / AGENT.md 原文 |
| profile create | 轻 | Files 写最小 AGENT.md 模板（幂等不覆盖；provider 缺省取 `yokeos.providers` 第一个，无配置报错） |
| profile delete | 轻 | Files 归档式移动 `.yokeos/agents/<name>/` → `.yokeos/archive/`（D6；同名带时间戳后缀） |
| provider list | 轻 | SnakeYAML 直读 application.yaml 的 `yokeos.providers`（列 name/base-url，不解析 key） |
| tool list | 轻 | 输出当前真实就绪内置工具（http_get）+ 「20 节接 ToolRegistry 后改查注册表」注明 |
| session list | 轻 | 纯只读 JDBC 查 sessions（session_id/agent_name/status/last_active_at，倒序前 20）；库不存在 → 「暂无会话」 |

统一行为：全部命令 `--help`（Picocli `mixinStandardHelpOptions`）；未知子命令/参数统一报错、退出码非 0、无堆栈。fat JAR mainClass = `com.yokeos.cli.YokeOsCli`。
