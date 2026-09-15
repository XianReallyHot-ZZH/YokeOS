package com.yokeos.core.audit;

import java.time.Instant;

/**
 * Tool 调用记录的只读投影（第 18 节 /tools 数据源；26 节 Web 会话详情可复用）。
 *
 * <p>字段与 tool_invocations 审计表列一一对应（去掉自增 id 与 session_id——按会话查询的语境下两者冗余）； 与写口 {@link
 * ToolInvocationAuditor} 同包对称：写口 17 节、读口 18 节。
 *
 * @param toolName 工具名
 * @param inputJson 原始入参 JSON 文本
 * @param success 成败标识
 * @param errorMessage 失败原因（成功时为 null）
 * @param durationMs 执行耗时（毫秒）
 * @param createdAt 调用时间
 */
public record ToolInvocationRecord(
    String toolName,
    String inputJson,
    boolean success,
    String errorMessage,
    long durationMs,
    Instant createdAt) {}
