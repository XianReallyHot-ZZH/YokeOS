-- memory_entries 表（第 22 节）：长期记忆条目（sqlite 档存储体）。
-- 列定义逐字来自 docs/TechnicalSolution.md §9.2；表结构唯一权威是手工脚本，禁 ddl-auto=update（宪法 7）。
-- markdown 档（MEMORY.md）与 mem0 档不使用此表——三档各自独立存储体，切换不迁移（specs/007 spec Clarifications 裁决）。

CREATE TABLE IF NOT EXISTS memory_entries (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    scope      VARCHAR(16) NOT NULL,
    content    TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_memory_scope ON memory_entries (scope);
