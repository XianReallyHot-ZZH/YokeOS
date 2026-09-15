-- sessions 表（第 18 节）：会话元数据 + JSON 序列化对话历史。
-- 列定义逐字来自 docs/TechnicalSolution.md §9.2；表结构唯一权威是手工脚本，禁 ddl-auto=update（宪法 7）。
-- 关联列名 agent_name 为技 §9.2 字面量（core 领域字段 profileName，storage 实体映射处注明——第 18 节拍板②）。

CREATE TABLE IF NOT EXISTS sessions (
    session_id     VARCHAR(255) PRIMARY KEY,
    agent_name     VARCHAR(128) NOT NULL,
    channel        VARCHAR(64) NOT NULL,
    user_id        VARCHAR(128) NOT NULL,
    messages_json  TEXT,
    status         VARCHAR(16) NOT NULL,
    created_at     TIMESTAMP NOT NULL,
    last_active_at TIMESTAMP,
    archived_at    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sessions_agent ON sessions (agent_name);
