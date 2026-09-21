package com.yokeos.core.session;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SessionManager 内存兜底实现：按 sessionId 存 Map（第 18 节随接口补全 getOrCreate/get；第 26 节随接口补全列表与归档）。
 *
 * <p>保留作测试与轻量场景用；生产装配走 storage 的 JPA 实现。虚拟线程并发下读写用 ConcurrentHashMap（宪法 4：并发靠虚拟线程，不引异步模型）；id 拼接走
 * {@link SessionIds} 单点，本类不复制公式（H4④）。
 *
 * <p>列表与归档（26 节）落在并行的摘要表上：领域 {@link Session} 不承载 status/channel 等元数据（归档是存储层标记， 不进对话状态——research
 * D4），摘要表在 getOrCreate 时登记、save 时刷最近活跃。
 */
public final class InMemorySessionManager implements SessionManager {

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  private final Map<String, SessionSummary> summaries = new ConcurrentHashMap<>();

  @Override
  public Session getOrCreate(String channel, String userId, String profileName) {
    String id = SessionIds.compose(channel, userId, profileName);
    Session session = sessions.computeIfAbsent(id, key -> new Session(key, profileName));
    summaries.computeIfAbsent(
        id,
        key ->
            new SessionSummary(
                key,
                profileName,
                channel,
                userId,
                SessionSummary.STATUS_ACTIVE,
                LocalDateTime.now()));
    return session;
  }

  @Override
  public Optional<Session> get(String sessionId) {
    return Optional.ofNullable(sessions.get(sessionId));
  }

  @Override
  public void save(Session session) {
    sessions.put(session.sessionId(), session);
    summaries.computeIfPresent(
        session.sessionId(), (id, summary) -> withLastActiveAt(summary, LocalDateTime.now()));
  }

  @Override
  public List<SessionSummary> listRecent(int limit) {
    return summaries.values().stream()
        .sorted(
            Comparator.comparing(
                    SessionSummary::lastActiveAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .reversed()) // 最近活跃在前；从未 save 过的（lastActiveAt=null）沉底
        .limit(Math.max(0, limit))
        .toList();
  }

  @Override
  public boolean archive(String sessionId) {
    return summaries.computeIfPresent(sessionId, (id, summary) -> withStatus(summary)) != null;
  }

  private static SessionSummary withStatus(SessionSummary summary) {
    return new SessionSummary(
        summary.sessionId(),
        summary.agentName(),
        summary.channel(),
        summary.userId(),
        SessionSummary.STATUS_ARCHIVED,
        summary.lastActiveAt());
  }

  private static SessionSummary withLastActiveAt(SessionSummary summary, LocalDateTime at) {
    return new SessionSummary(
        summary.sessionId(),
        summary.agentName(),
        summary.channel(),
        summary.userId(),
        summary.status(),
        at);
  }
}
