package com.yokeos.core.audit;

/**
 * LLM 调用审计契约（宪法 7：审计 day one，成败都落库）。
 *
 * <p>跨模块契约落位规则：core 定义、yokeos-storage 实现（JPA）、yokeos-provider 消费（依赖倒置， research D3）。参数用显式整数而非框架
 * Usage 类型——core 不引 Spring AI（模块规则）。 实现方必须保证：写入失败不吞（上抛或落错误日志），绝不静默丢审计。
 */
public interface LlmCallAuditor {

  /**
   * 记一次 LLM 调用。成败各记一行：成功时 errorMessage 为 null；失败时 errorMessage 必非空。
   *
   * @param promptTokens 输入 token 数（可空——失败路径取不到）
   * @param completionTokens 输出 token 数（可空）
   * @param totalTokens 总 token 数（可空）
   * @param durationMs 调用耗时毫秒
   */
  void record(
      String sessionId,
      String provider,
      String model,
      Integer promptTokens,
      Integer completionTokens,
      Integer totalTokens,
      boolean success,
      String errorMessage,
      long durationMs);
}
