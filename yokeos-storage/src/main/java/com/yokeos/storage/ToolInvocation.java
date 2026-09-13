package com.yokeos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * {@code tool_invocations} 审计表实体（宪法 7：成败都落库，第 17 节起写入）。列定义的唯一权威是手工脚本 {@code
 * db/schema-001-audit.sql}（16 节已建、禁 ddl-auto=update），本实体的列注解与脚本逐列对齐；Sandbox 拒绝也走 此表（{@code
 * success=false}，24 节接线）。
 */
@Entity
@Table(name = "tool_invocations")
public class ToolInvocation {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "session_id", nullable = false)
  private String sessionId;

  @Column(name = "tool_name", nullable = false, length = 128)
  private String toolName;

  @Column(name = "input_json")
  private String inputJson;

  @Column(name = "result_json")
  private String resultJson;

  @Column(nullable = false)
  private Boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "duration_ms", nullable = false)
  private Long durationMs;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  /** JPA 需要的无参构造；业务构造走 {@link JpaToolInvocationAuditor} 的字段装配。 */
  protected ToolInvocation() {}

  /** 返回自增主键。 */
  public Long getId() {
    return id;
  }

  public String getSessionId() {
    return sessionId;
  }

  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  public String getToolName() {
    return toolName;
  }

  public void setToolName(String toolName) {
    this.toolName = toolName;
  }

  public String getInputJson() {
    return inputJson;
  }

  public void setInputJson(String inputJson) {
    this.inputJson = inputJson;
  }

  public String getResultJson() {
    return resultJson;
  }

  public void setResultJson(String resultJson) {
    this.resultJson = resultJson;
  }

  public Boolean getSuccess() {
    return success;
  }

  public void setSuccess(Boolean success) {
    this.success = success;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  public Long getDurationMs() {
    return durationMs;
  }

  public void setDurationMs(Long durationMs) {
    this.durationMs = durationMs;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }
}
