package com.yokeos.core.tool;

/**
 * 工具执行结果（第 17 节拍板②）：content / success / errorMessage / retryable 四字段。
 *
 * <p>retryable 是 ToolExecutor 退避策略的输入（可重试失败指数退避，需 §8.2）；成败都回填进 Session—— 模型下一轮能看到失败原因并自行决定下一步。
 */
public record ToolResult(String content, boolean success, String errorMessage, boolean retryable) {

  /** 成功工厂。 */
  public static ToolResult ok(String content) {
    return new ToolResult(content, true, null, false);
  }

  /**
   * 失败工厂。
   *
   * @param retryable 是否可重试（网络类瞬态失败为 true；未注册名、坏参数等确定性失败为 false）
   */
  public static ToolResult error(String errorMessage, boolean retryable) {
    return new ToolResult(null, false, errorMessage, retryable);
  }
}
