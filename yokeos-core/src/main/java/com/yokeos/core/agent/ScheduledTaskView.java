package com.yokeos.core.agent;

import java.time.Instant;

/** scheduled_tasks 的只读投影（技 §9.2 全列）。值对象跨模块传递，30 节管理端点与排障复用； 列语义见建表脚本 schema-004-scheduler.sql。 */
public record ScheduledTaskView(
    String taskId,
    String profileName,
    String cron,
    String zone,
    String message,
    boolean enabled,
    Instant nextRunAt,
    Instant lastRunAt,
    String lastStatus,
    long runCount,
    Instant updatedAt) {}
