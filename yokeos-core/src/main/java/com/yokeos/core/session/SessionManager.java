package com.yokeos.core.session;

/**
 * 会话存取契约（第 17 节最小面：仅 save——AgentService 正常路径出口调用）。
 *
 * <p>getOrCreate 与持久化实现归第 18 节（SQLite sessions 表）；内存实现先兜底装配。
 */
public interface SessionManager {

  /** 保存会话（累积完的历史持久化；实现自辨覆盖或追加语义）。 */
  void save(Session session);
}
