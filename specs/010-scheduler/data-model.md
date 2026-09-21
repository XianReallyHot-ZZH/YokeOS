# Data Model: 定时任务（第25节）

**Phase 1 产出**。列定义逐字来自 `docs/TechnicalSolution.md` §9.2（task_id 行已按拍板①修订为派生生成）；表结构唯一权威是手工脚本 `yokeos-storage/src/main/resources/db/schema-004-scheduler.sql`（宪法 7，禁 ddl-auto=update）。

## 实体关系

```text
AGENT.md frontmatter（定义源，不落表）
  └─ Profile.schedules: List<ScheduleConfig(cron, zone, message)>   [16 节已建全，零改动]
       └─ 派生 task_id = "{profileName}#{声明序号}"（从 1 起）        [拍板①]
            ├── scheduled_tasks.task_id（主键，1 : N 反向）
            ├── task_executions.task_id（外键语义，N : 1）
            ├── 进程内锁表 key（ConcurrentMap<String, Lock>）
            └── 句柄表 key（Map<String, ScheduledFuture<?>>，29/30 节注销用）
```

## scheduled_tasks（任务登记 + 运行状态）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `task_id` | VARCHAR | **主键** | 派生成 `{profileName}#{声明序号}`（拍板①） |
| `profile_name` | VARCHAR | NOT NULL | 归属 Profile |
| `cron` | VARCHAR | NOT NULL | cron 表达式（Spring 六字段） |
| `zone` | VARCHAR | 可空 | 时区（空 = 系统时区回退，运行时解析） |
| `message` | TEXT | 可空 | 到点发给 Agent 的消息 |
| `enabled` | BOOLEAN | NOT NULL | 默认启用（新登记 true） |
| `next_run_at` | TIMESTAMP | 可空 | 下次触发时刻（非法配置 null，research D9） |
| `last_run_at` | TIMESTAMP | 可空 | 上次触发时刻 |
| `last_status` | VARCHAR | 可空 | `success` / `failed` 字面 |
| `run_count` | BIGINT | NOT NULL | 累计触发次数（默认 0，reconcile 不冲掉） |
| `updated_at` | TIMESTAMP | NOT NULL | 状态更新时间 |

**生命周期**：注册时 reconcile（无则插入 enabled=true/run_count=0，有则只更新定义字段 + `next_run_at`/`updated_at`，**保留 enabled 与 run_count**——重启不丢）；执行后 recordExecution 更新 `last_run_at`/`last_status`/`run_count+1`/`next_run_at`。文件里已删的任务**留档不删**（孤儿行无害，重启不注册）。

## task_executions（执行历史，成功失败都记）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| `id` | INTEGER | 主键 AUTOINCREMENT | |
| `task_id` | VARCHAR | NOT NULL | 关联 `scheduled_tasks`（逻辑外键，无 FK 约束——SQLite 手工脚本惯例，与审计两表同款） |
| `session_id` | VARCHAR | 可空 | 本次触发所用钟推 Session（`process` 抛在拿 Session 之前时为 null） |
| `started_at` | TIMESTAMP | NOT NULL | 开始时间 |
| `success` | BOOLEAN | NOT NULL | 成败 |
| `error_message` | VARCHAR | 可空 | 失败信息 |
| `duration_ms` | BIGINT | NOT NULL | 执行耗时 |

索引：`idx_task_executions_task (task_id)`（`executions(taskId, limit)` 查询路径）。

## 只读视图（core 值对象，30 节管理端点复用）

- **`ScheduledTaskView`**（record）：`scheduled_tasks` 全列投影——`taskId/profileName/cron/zone/message/enabled/nextRunAt/lastRunAt/lastStatus/runCount/updatedAt`。
- **`TaskExecutionView`**（record）：`task_executions` 全列投影——`id/taskId/sessionId/startedAt/success/errorMessage/durationMs`。

## 校验规则（源自 spec FR）

- 非法 cron/zone：单条记日志跳过注册（FR-007），不拖垮其它条；`next_run_at` 可空。
- 停用任务（`enabled=false`）：到点跳过、**不写** `task_executions`（FR-009，停用 ≠ 失败）。
- `session_id`：钟推三元组 `(scheduler, scheduler, profileName)` 经 18 节 `SessionIds` 单点拼接，调度器不生成 session_id（FR-006）。
