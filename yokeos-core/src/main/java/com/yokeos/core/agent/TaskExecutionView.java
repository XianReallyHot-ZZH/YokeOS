package com.yokeos.core.agent;

import java.time.Instant;

/** task_executions 的只读投影（技 §9.2 全列）：一次定时触发的执行记录，成功失败都留。 */
public record TaskExecutionView(
    long id,
    String taskId,
    String sessionId,
    Instant startedAt,
    boolean success,
    String errorMessage,
    long durationMs) {}
