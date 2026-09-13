package com.yokeos.core.session;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SessionManager 内存兜底实现：按 sessionId 存 Map。
 *
 * <p>第 18 节换 JPA 实现时只换装配方，本类保留作测试与轻量场景用。虚拟线程并发下读写用 ConcurrentHashMap（宪法 4：并发靠虚拟线程，不引异步模型）。
 */
public final class InMemorySessionManager implements SessionManager {

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  @Override
  public void save(Session session) {
    sessions.put(session.sessionId(), session);
  }

  /** 测试与冒烟观察用：按 sessionId 取会话。 */
  public Session get(String sessionId) {
    return sessions.get(sessionId);
  }
}
