-- scheduled_tasks / task_executions 两表（第 25 节）：定时任务登记状态与执行历史（技 §8.5 / §9.2）。
-- 列定义逐字来自 docs/TechnicalSolution.md §9.2（task_id 派生生成 "{profileName}#{声明序号}"——25 节拍板①）；表结构唯一权威是手工脚本，禁 ddl-auto=update（宪法 7）。
-- 定义源仍是 AGENT.md frontmatter 的 schedules，两表只存「状态 + 历史」；文件里已删除的任务留档不删（孤儿行无害，重启不注册）。

-- scheduled_tasks：任务登记 + 运行状态（reconcile 幂等登记，保留 enabled 与 run_count——重启不丢）
CREATE TABLE IF NOT EXISTS scheduled_tasks (
    task_id      VARCHAR(128) PRIMARY KEY,
    profile_name VARCHAR(128) NOT NULL,
    cron         VARCHAR(64) NOT NULL,
    zone         VARCHAR(64),
    message      TEXT,
    enabled      BOOLEAN NOT NULL DEFAULT 1,
    next_run_at  TIMESTAMP,
    last_run_at  TIMESTAMP,
    last_status  VARCHAR(16),
    run_count    BIGINT NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP NOT NULL
);

-- task_executions：每次执行一条，成功失败都记（与审计两表同源的留痕纪律）
CREATE TABLE IF NOT EXISTS task_executions (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    task_id       VARCHAR(128) NOT NULL,
    session_id    VARCHAR(255),
    started_at    TIMESTAMP NOT NULL,
    success       BOOLEAN NOT NULL,
    error_message TEXT,
    duration_ms   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_task_executions_task ON task_executions (task_id);
