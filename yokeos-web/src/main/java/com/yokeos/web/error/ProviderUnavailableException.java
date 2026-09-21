package com.yokeos.web.error;

/**
 * Provider 故障（第 26 节）：显式 503 语义，与既有 {@code IllegalStateException}→503 映射并列（技 §7.4 错误码口径）——
 * 类型化后调用方与测试可精确断言，不再依赖消息内容判因。
 */
public class ProviderUnavailableException extends RuntimeException {

  /** 构造异常，消息进统一信封的 message 字段。 */
  public ProviderUnavailableException(String message) {
    super(message);
  }
}
