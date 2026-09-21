package com.yokeos.web.error;

/**
 * 领域资源不存在（第 26 节，如 Agent 名未注册）：全局异常处理器转 404——资源缺失不是服务不可用， 不能让它落进 {@code IllegalStateException}→503
 * 的口径（坑三）。
 */
public class ResourceNotFoundException extends RuntimeException {

  /** 构造异常，消息进统一信封的 message 字段。 */
  public ResourceNotFoundException(String message) {
    super(message);
  }
}
