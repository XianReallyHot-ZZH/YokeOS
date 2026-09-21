package com.yokeos.web.error;

/** 会话不存在（第 26 节）：发消息 / 查历史 / 归档打到不存在的 sessionId 时抛出， 全局异常处理器转 404。 */
public class SessionNotFoundException extends RuntimeException {

  /** 构造异常，消息进统一信封的 message 字段。 */
  public SessionNotFoundException(String sessionId) {
    super("会话不存在: " + sessionId);
  }
}
