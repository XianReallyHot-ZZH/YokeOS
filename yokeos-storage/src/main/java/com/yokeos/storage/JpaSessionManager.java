package com.yokeos.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yokeos.core.session.Message;
import com.yokeos.core.session.SessionIds;
import com.yokeos.core.session.SessionSummary;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * SessionManager 的 JPA 实现（第 18 节）：sessions 表持久化 + 跨重启恢复。
 *
 * <p>id 拼接走 core {@link SessionIds} 单点（H4④：全库唯一拼接点，本类与其余入口都不自拼公式）； 对话历史整体 JSON 序列化存 messages_json
 * 一列，回读用 TypeReference 还原 List&lt;Message&gt;（17 节 role 用 String 的设计在此兑现零转换）。 实体列 agent_name ↔ core
 * 字段 profileName：技 §9.2 定的列名字面量（拍板②），映射只发生在本类两处（toEntity 的 set、getOrCreate 的入参）。
 */
public class JpaSessionManager implements com.yokeos.core.session.SessionManager {

  private final SessionRepository repository;

  private final ObjectMapper mapper = new ObjectMapper();

  /**
   * @param repository sessions 仓库（事务由 repository 自带语义承担，本类不自带 @Transactional）。
   */
  public JpaSessionManager(SessionRepository repository) {
    this.repository = repository;
  }

  @Override
  public com.yokeos.core.session.Session getOrCreate(
      String channel, String userId, String profileName) {
    String id = SessionIds.compose(channel, userId, profileName);
    Optional<Session> existing = repository.findById(id);
    if (existing.isPresent()) {
      return restore(existing.get());
    }
    Session entity = new Session();
    entity.setSessionId(id);
    entity.setAgentName(profileName); // agent_name 列 ↔ profileName 字段（拍板②）
    entity.setChannel(channel);
    entity.setUserId(userId);
    entity.setStatus("active");
    try {
      repository.save(entity);
    } catch (DataIntegrityViolationException e) {
      // 并发兜底（spec Edge）：主键即三元组拼接结果，另一线程已建即复用，不产生第二条
      return repository
          .findById(id)
          .map(this::restore)
          .orElseThrow(() -> new IllegalStateException("并发建会话后重查仍为空", e));
    }
    return new com.yokeos.core.session.Session(id, profileName);
  }

  @Override
  public Optional<com.yokeos.core.session.Session> get(String sessionId) {
    return repository.findById(sessionId).map(this::restore);
  }

  @Override
  public void save(com.yokeos.core.session.Session session) {
    Session entity =
        repository
            .findById(session.sessionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "会话不存在，save 前必须先 getOrCreate: " + session.sessionId()));
    entity.setMessagesJson(writeMessages(session.messages()));
    entity.setLastActiveAt(LocalDateTime.now());
    repository.save(entity);
  }

  /**
   * 列最近会话摘要（第 26 节）：last_active_at 倒序 + 分页取前 limit 条。摘要只映射元数据列， 不走 {@link #restore}——不触发
   * messages_json 反序列化（data-model 不变量④）。
   */
  @Override
  public List<SessionSummary> listRecent(int limit) {
    return repository
        .findAll(
            PageRequest.of(0, Math.max(0, limit), Sort.by(Sort.Direction.DESC, "lastActiveAt")))
        .getContent()
        .stream()
        .map(
            row ->
                new SessionSummary(
                    row.getSessionId(),
                    row.getAgentName(),
                    row.getChannel(),
                    row.getUserId(),
                    row.getStatus(),
                    row.getLastActiveAt()))
        .toList();
  }

  /**
   * 归档（第 26 节，DELETE 端点）：status 置 archived + 写 archived_at；未命中 false。 归档是标记不是终结——getOrCreate 的
   * findById 不查 status，同三元组仍幂等返回本会话（research D4）。
   */
  @Override
  public boolean archive(String sessionId) {
    return repository
        .findById(sessionId)
        .map(
            row -> {
              row.setStatus(SessionSummary.STATUS_ARCHIVED);
              row.setArchivedAt(LocalDateTime.now());
              repository.save(row);
              return true;
            })
        .orElse(false);
  }

  /** 实体行恢复为领域 Session：messages_json 反序列化回历史（恢复构造器——恢复后追加不覆盖）。 */
  private com.yokeos.core.session.Session restore(Session entity) {
    return new com.yokeos.core.session.Session(
        entity.getSessionId(), entity.getAgentName(), readMessages(entity.getMessagesJson()));
  }

  private String writeMessages(java.util.List<Message> messages) {
    try {
      return mapper.writeValueAsString(messages);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("会话历史序列化失败", e);
    }
  }

  private java.util.List<Message> readMessages(String messagesJson) {
    if (messagesJson == null || messagesJson.isBlank()) {
      return java.util.List.of();
    }
    try {
      return mapper.readValue(messagesJson, new TypeReference<java.util.List<Message>>() {});
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("会话历史反序列化失败", e);
    }
  }
}
