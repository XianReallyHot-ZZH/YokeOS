package com.yokeos.core.audit;

/**
 * 工具调用审计契约（与 16 节 LlmCallAuditor 同包对称；core 定义、storage 实现、ToolExecutor 消费）。
 *
 * <p>列定义逐字技术方案 §9.2 的 tool_invocations（session_id / tool_name / input_json / result_json / success
 * / error_message / duration_ms / created_at）。成败都记一行最终态（一次调用请求一条，重试是 执行内部策略）；写入失败不吞异常（宪法 7：审计 day
 * one，Sandbox 拒绝也走此表——24 节接线）。
 */
public interface ToolInvocationAuditor {

  /**
   * 记一行工具调用审计（先落账再还结果）。
   *
   * @param sessionId 审计关联键
   * @param toolName 工具名
   * @param inputJson 原始入参 JSON 文本
   * @param resultJson 执行结果（成功存 content、失败存错误描述）
   * @param success 成败标识
   * @param errorMessage 失败原因（成功时为 null）
   * @param durationMs 执行耗时（重试场景覆盖全部尝试总耗时）
   */
  void record(
      String sessionId,
      String toolName,
      String inputJson,
      String resultJson,
      boolean success,
      String errorMessage,
      long durationMs);
}
