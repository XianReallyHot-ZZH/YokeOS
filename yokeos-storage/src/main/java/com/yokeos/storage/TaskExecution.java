package com.yokeos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * task_executions：定时任务每次执行的历史（第 25 节，技 §9.2）——成功失败都记，与审计两表同源的留痕纪律 （失败事故必须在库里有痕迹）。表结构唯一权威是手工脚本
 * schema-004-scheduler.sql（宪法 7）。
 */
@Entity
@Table(name = "task_executions")
public class TaskExecution {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "task_id", nullable = false)
  private String taskId;

  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "started_at", nullable = false)
  private LocalDateTime startedAt;

  @Column(nullable = false)
  private boolean success;

  @Column(name = "error_message")
  private String errorMessage;

  @Column(name = "duration_ms", nullable = false)
  private long durationMs;

  /** 主键（自增）。 */
  public Long getId() {
    return id;
  }

  /** 关联 scheduled_tasks 的派生任务标识（逻辑外键，与审计两表同款不设约束）。 */
  public String getTaskId() {
    return taskId;
  }

  /** 关联任务标识。 */
  public void setTaskId(String taskId) {
    this.taskId = taskId;
  }

  /** 本次触发所用的钟推会话标识（执行抛在拿到会话之前时为 null）。 */
  public String getSessionId() {
    return sessionId;
  }

  /** 钟推会话标识。 */
  public void setSessionId(String sessionId) {
    this.sessionId = sessionId;
  }

  /** 开始时间（executions 倒序排序承载）。 */
  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  /** 开始时间。 */
  public void setStartedAt(LocalDateTime startedAt) {
    this.startedAt = startedAt;
  }

  /** 是否成功。 */
  public boolean isSuccess() {
    return success;
  }

  /** 是否成功。 */
  public void setSuccess(boolean success) {
    this.success = success;
  }

  /** 失败信息（可空）。 */
  public String getErrorMessage() {
    return errorMessage;
  }

  /** 失败信息（可空）。 */
  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  /** 执行耗时（毫秒）。 */
  public long getDurationMs() {
    return durationMs;
  }

  /** 执行耗时（毫秒）。 */
  public void setDurationMs(long durationMs) {
    this.durationMs = durationMs;
  }
}
