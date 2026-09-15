package com.yokeos.core.session;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SessionManager 内存兜底实现：按 sessionId 存 Map（第 18 节随接口补全 getOrCreate/get）。
 *
 * <p>保留作测试与轻量场景用；生产装配走 storage 的 JPA 实现。虚拟线程并发下读写用 ConcurrentHashMap（宪法 4：并发靠虚拟线程，不引异步模型）；id 拼接走
 * {@link SessionIds} 单点，本类不复制公式（H4④）。
 */
public final class InMemorySessionManager implements SessionManager {

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  @Override
  public Session getOrCreate(String channel, String userId, String profileName) {
    String id = SessionIds.compose(channel, userId, profileName);
    return sessions.computeIfAbsent(id, key -> new Session(key, profileName));
  }

  @Override
  public Optional<Session> get(String sessionId) {
    return Optional.ofNullable(sessions.get(sessionId));
  }

  @Override
  public void save(Session session) {
    sessions.put(session.sessionId(), session);
  }
}
