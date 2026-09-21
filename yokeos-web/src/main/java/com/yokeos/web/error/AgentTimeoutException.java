package com.yokeos.web.error;

/**
 * Agent 调用超时（第 26 节，504 口径占位）：同步 + 虚拟线程模型不造硬中断（宪法 4），真实超时由 provider 层承载（17 节已收紧 retry/超时）；本异常保留 504
 * 映射位，供未来接入与测试断言（research D10）。
 */
public class AgentTimeoutException extends RuntimeException {

  /** 构造异常，消息进统一信封的 message 字段。 */
  public AgentTimeoutException(String message) {
    super(message);
  }
}
