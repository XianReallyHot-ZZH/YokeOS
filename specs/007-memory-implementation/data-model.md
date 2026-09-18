# Data Model: Memory 两层记忆（第22节，specs/007）

> Phase 1 产出。列定义逐字来自技 §9.2/§5.2 与 specs/006 D5/D9；表结构唯一权威是手工脚本（schema-003-memory.sql），禁 `ddl-auto=update`（宪法 7）。

## 1. 新增表：`memory_entries`（仅 sqlite 档使用）

```sql
-- memory_entries 表（第 22 节）：长期记忆条目（sqlite 档存储体）。
-- 列定义逐字来自 docs/TechnicalSolution.md §9.2；表结构唯一权威是手工脚本，禁 ddl-auto=update（宪法 7）。
-- markdown 档（MEMORY.md）与 mem0 档不使用此表——三档各自独立存储体，切换不迁移（spec Clarifications 裁决）。

CREATE TABLE IF NOT EXISTS memory_entries (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    scope      VARCHAR(16) NOT NULL,
    content    TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_memory_scope ON memory_entries (scope);
```

| 字段 | 类型 | 约束 | 说明 |
|------|------|------|------|
| `id` | INTEGER | PK，自增 | 主键 |
| `scope` | VARCHAR(16) | NOT NULL | `CORE` / `ARCHIVAL`（两分区语义的 sqlite 落地，specs/006 D5） |
| `content` | TEXT | NOT NULL | 记忆内容（写入时已由 Tool 层保证非空） |
| `created_at` | TIMESTAMP | NOT NULL | 写入时间（排序承载「保留最近」语义） |

**查询口径**（契约二/四的 SQL 结构保证）：核心区 `WHERE scope='CORE'` 全量（物理上无 LIMIT）；归档区 `Pageable`（0..archive-max-rows，`created_at DESC`）；检索 `scope='ARCHIVAL' AND content LIKE '%kw%' ESCAPE '\'`（关键词先转义 `\`/`%`/`_`）。

## 2. 文件格式：`MEMORY.md`（markdown 档存储体）

```markdown
## 核心记忆
- [2026-09-18] 用户的项目用 Java 21，部署在 K8s
- [2026-09-18] 用户偏好中文交流

## 归档记忆
- [2026-09-17] 讨论过 SQLite 与 H2 的取舍，结论 SQLite
```

| 约定 | 内容 | 出处 |
|------|------|------|
| 分区 header | `## 核心记忆` / `## 归档记忆` 两个，字面量定死 | specs/006 D5 |
| 条目格式 | `- [yyyy-MM-dd] 内容`，写入自动附日期 | 技 §5.1（append 自动加日期 header） |
| 格式严格度 | 不做更严格规定——Agent 写什么 LLM 自己理解 | 技 §5.2 |
| 截断 | 归档区段超 `archive-max-chars`（默认 4000）从尾部保留最近字符；**核心区段永不被裁**（裁剪函数只接收归档段文本） | 契约二 |
| 检索 | 只读归档区段做行包含匹配 | 契约四 |
| 空态 | 文件不存在视作空记忆，不报错；首次写入时创建两分区结构 | spec Edge Case |

## 3. 值对象：`MemoryScope`

| 值 | 语义 | 注入行为 | 检索行为 |
|----|------|---------|---------|
| `CORE` | 核心区（用户是谁、项目背景、关键偏好） | 全量注入、永不截断 | 不参与检索（本来就在场） |
| `ARCHIVAL` | 归档区（过程性记忆，缺省分区） | 截断后注入（保留最近） | 关键词检索的唯一对象 |

## 4. 配置键：`yokeos.memory.*`

| 键 | 类型 | 缺省 | 说明 |
|----|------|------|------|
| `yokeos.memory.backend` | String | `markdown` | 后端选档：`markdown` / `sqlite` / `mem0`；换档只改此行（FR6） |
| `yokeos.memory.archive-max-chars` | int | `4000` | markdown 档归档区字符阈值（O1 落定，教学文档拍板②） |
| `yokeos.memory.archive-max-rows` | int | `100` | sqlite 档归档区保留行数（LIMIT） |
| `yokeos.memory.mem0.base-url` | String | `${MEM0_BASE_URL}` 占位 | mem0 档实例地址；**不走 Boot 绑定解析**（research D6），切档使用时解析 |
| `yokeos.memory.mem0.api-key` | String | `${MEM0_API_KEY}` 占位 | mem0 档凭证；同上，缺失时清晰报错不静默 |

## 5. 既有数据（本节复用、不改动）

| 数据 | 关系 |
|------|------|
| `sessions`（18 节） | 会话记忆存储体——门面 D10 复用，本节零改动；`buildContext` 不读它（会话历史经 PromptBuilder 既有 [3] 段承载） |
| `tool_invocations`（16 节建表） | `save_memory`/`recall_memory` 调用留痕走 ToolExecutor 既有审计路径，零新增逻辑 |
| `USER.md`（Bootstrap） | 只读边界：Memory 写路径物理上不可触碰（坑三）；与 MEMORY.md 来源/生命周期不同（技 §5.4） |
