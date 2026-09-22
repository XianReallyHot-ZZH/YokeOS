# Data Model: 插件化 Agent（第29节）

**Branch**: `specs/014-plugin-agent` | **Date**: 2026-09-22

本节零新表、零 schema 变更（SQLite 侧完全不动）。数据面增量是**文件系统实体**与**内存结构的行为扩展**：

## 文件系统实体

### SKILL.md（本节新消费面）

| 属性 | 说明 | 校验/约定 |
|------|------|----------|
| 位置 | `.yokeos/skills/<名>/SKILL.md` | 目录名 = 按名引用的键（frontmatter `name` 仅元数据，不作键——research D1） |
| frontmatter | `name` / `description`（agentskills.io 兼容） | 只剥不解析；缺围栏按无 frontmatter 处理（17 节剥壳鲁棒性沿用） |
| 正文 | 注入 system prompt 的规范本体 | 整段注入（第一阶段形态）；agentskills.io 开放格式 |

**生命周期**（纯文件态，无状态机）：文件就位 → 可被点名注入；删除/缺失 → WARN 有痕跳过（research D2）；读失败 → 组装显式抛错（research D3）。零缓存——每次组装现取，改完下次生效。

### Agent 目录（既有，本节零改动）

`.yokeos/agents/<name>/`：`AGENT.md`（frontmatter + 正文）+ 可选 `scripts/`、`REFERENCE.md`、`skills/`（参照形态残留——属附属资源不注入，research D8）。

### 示例资产 `daily-reconcile`（本节交付）

`yokeos-core/src/test/resources/fixture/029/workspace-example/` 下四件套：`agents/daily-reconcile/`（AGENT.md + REFERENCE.md + scripts/reconcile.py）与 `skills/report-format/SKILL.md`。AGENT.md frontmatter 关键段：`skills: [report-format]`（按名引用）、`schedules`（id `reconcile-morning`，cron `0 0 9 * * *`，zone Asia/Shanghai）、`notify.channels`（webhook，`${OPS_WEBHOOK_URL}` 占位不落明文）。

## 内存结构（行为扩展）

### ProfileRegistry（16 节既有 ConcurrentHashMap）

| 方法 | 语义 | 本节状态 |
|------|------|---------|
| `register(Profile)` | 覆盖同名（后到者胜） | 既有；javadoc 补 29 节定夺（覆盖是 30 节 PUT 更新的基础，完整冲突策略扩展阶段） |
| `get(String)` / `all()` | 按名查 / 快照 | 既有 |
| `exists(String)` | 按名查存在 → boolean | **新增** |
| `remove(String)` | 存在移除返回 true；重复 remove 返回 false（幂等） | **新增** |

### AgentScheduler 句柄表（25 节既有 `Map<String, ScheduledFuture<?>>`）

- key = taskId，派生 `{profileName}:{id}`——注册/注销**共用同一私有派生方法**（research D6，防漂移）。
- 状态转移：注册（`registerProfile`）→ 留句柄；注销（`unregisterProfile`，本节新增）→ `cancel(false)` + 移除句柄；句柄不存在（重复注销/未注册）→ 静默无操作。
- `scheduled_tasks` / `task_executions` 两表**零变更**：注销只动内存句柄，表行状态语义归 30 节 DELETE 编排统一处理。

## 验证规则（来自 spec FR）

- frontmatter `skills` 列表 → 逐名注入；点名不存在 → WARN 跳过（不抛、不阻断）。
- 注入序固定：identity → Bootstrap → Skill 段（声明序）→ AGENT.md 正文。
- 附属资源（scripts/、REFERENCE.md、Agent 目录内 skills/）零预载。
