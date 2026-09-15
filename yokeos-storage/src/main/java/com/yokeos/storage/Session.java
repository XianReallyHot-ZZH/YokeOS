package com.yokeos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * sessions 表 JPA 实体（第 18 节）：列定义逐字技 §9.2 / db/schema-002-sessions.sql。 与 core 领域对象
 * com.yokeos.core.session.Session 同名不同包（参照先例）——JpaSessionManager 内全限定引用本实体、 import core
 * 类型；agent_name 列 ↔ core profileName 字段的映射在 JpaSessionManager 注明（技 §9.2 字面量，拍板②）。
 */
@Entity
@Table(name = "sessions")
public class Session {

  @Id
  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "agent_name", nullable = false)
  private String agentName;

  @Column(name = "channel", nullable = false)
  private String channel;

  @Column(name = "user_id", nullable = false)
  private String userId;

  /** 对话历史整体 JSON 序列化（List&lt;Message&gt; 一列，核心阶段不按条拆表）。 */
  @Column(name = "messages_json")
  private String messagesJson;

  /** active（本节唯一取值）/ archived（26 节 DELETE 端点写入）。 */
  @Column(name = "status", nullable = false)
  private String status;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "last_active_at")
  private LocalDateTime lastActiveAt;

  @Column(name = "archived_at")
  private LocalDateTime archivedAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
    if (status == null) {
      status = "active";
    }
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getAgentName() {
    return agentName;
  }

  public void setAgentName(String agentName) {
    this.agentName = agentName;
  }

  public String getChannel() {
    return channel;
  }

  public void setChannel(String channel) {
    this.channel = channel;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getMessagesJson() {
    return messagesJson;
  }

  public void setMessagesJson(String messagesJson) {
    this.messagesJson = messagesJson;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getLastActiveAt() {
    return lastActiveAt;
  }

  public void setLastActiveAt(LocalDateTime lastActiveAt) {
    this.lastActiveAt = lastActiveAt;
  }

  public LocalDateTime getArchivedAt() {
    return archivedAt;
  }

  public void setArchivedAt(LocalDateTime archivedAt) {
    this.archivedAt = archivedAt;
  }
}
