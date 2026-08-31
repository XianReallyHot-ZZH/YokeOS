-- 审计两表预留位（宪法 7：审计 day one 落库，不以「日志够了」推迟；写入自第 16 节起）。
-- 列定义逐字来自 docs/TechnicalSolution.md §9.2；表结构唯一权威是手工脚本，禁 ddl-auto=update。
-- 本文件现阶段不接执行器（地基无数据源）；第 16 节接 SQLite 时并入启动执行，
-- 届时与后续建表脚本合并为 classpath 根的 schema.sql（参照库形态，spring.sql.init.mode=always + 幂等建表）。

-- tool_invocations：每次 Tool 调用记录（审计；Sandbox 拒绝也走此表 success=false）
CREATE TABLE IF NOT EXISTS tool_invocations (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id    VARCHAR(255) NOT NULL,
    tool_name     VARCHAR(128) NOT NULL,
    input_json    TEXT,
    result_json   TEXT,
    success       BOOLEAN NOT NULL,
    error_message TEXT,
    duration_ms   INTEGER NOT NULL,
    created_at    TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tool_invocations_session ON tool_invocations (session_id);

-- llm_calls：每次 LLM 调用记录（审计；token 用量 + 耗时）
CREATE TABLE IF NOT EXISTS llm_calls (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id        VARCHAR(255) NOT NULL,
    provider          VARCHAR(64) NOT NULL,
    model             VARCHAR(128) NOT NULL,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    duration_ms       INTEGER NOT NULL,
    created_at        TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_llm_calls_session ON llm_calls (session_id);
