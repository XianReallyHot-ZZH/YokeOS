package com.yokeos.tool.sandbox;

/**
 * 沙箱校验失败（specs/008 D4/D6、宪法 7）：从工具方法上抛终止动作，被 ToolExecutor 既有 catch 转为不可重试失败结果 落 {@code
 * tool_invocations}（success=false + error_message）——复用既有失败审计路径，不为 Sandbox 单增审计逻辑。
 *
 * <p>消息口径（research D7）：编译期常量前缀 + 动态目标经构造器拼接（CRLF 门禁唯一通过形态，19/20 节同款）；域名拒绝 只携带 host 不携带整串 URL、URL
 * 解析失败不回显原文——webhook URL 本身即凭证，不得进 error_message 与日志（19 节 坑二）。
 */
public class SandboxViolationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message 常量前缀 + 动态目标的拼接（见类注释的消息口径）
   */
  public SandboxViolationException(String message) {
    super(message);
  }

  /**
   * fail-closed 包装用：校验过程自身的 IO 异常等转成本异常上抛（research D2）。
   *
   * @param message 常量前缀消息
   * @param cause 根因（真实化失败/URL 解析失败等）
   */
  public SandboxViolationException(String message, Throwable cause) {
    super(message, cause);
  }
}
