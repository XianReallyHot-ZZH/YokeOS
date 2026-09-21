package com.yokeos.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * scheduled_tasks：定时任务登记 + 运行状态（第 25 节，技 §9.2）——表结构唯一权威是手工脚本 schema-004-scheduler.sql（宪法 7，不让
 * Hibernate 自动建）。
 *
 * <p>POJO + getter/setter 与 Session/LlmCall 同款（JPA 惯例）；时间列 LocalDateTime 与既有表一致， Instant 边界转换在
 * {@link JpaScheduledTaskStore} 收口。定义字段（cron/zone/message）由 reconcile 更新， 运行字段 （enabled/run_count
 * 等）只随执行与启停演进——两组生命周期不同，更新路径也不同。
 */
@Entity
@Table(name = "scheduled_tasks")
public class ScheduledTask {

  @Id
  @Column(name = "task_id")
  private String taskId;

  @Column(name = "profile_name", nullable = false)
  private String profileName;

  @Column(nullable = false)
  private String cron;

  @Column private String zone;

  @Column private String message;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "next_run_at")
  private LocalDateTime nextRunAt;

  @Column(name = "last_run_at")
  private LocalDateTime lastRunAt;

  @Column(name = "last_status")
  private String lastStatus;

  @Column(name = "run_count", nullable = false)
  private long runCount;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  /** 主键：派生任务标识 "{profileName}#{声明序号}"（25 节拍板①）。 */
  public String getTaskId() {
    return taskId;
  }

  /** 主键：派生任务标识。 */
  public void setTaskId(String taskId) {
    this.taskId = taskId;
  }

  /** 归属 Profile 名。 */
  public String getProfileName() {
    return profileName;
  }

  /** 归属 Profile 名。 */
  public void setProfileName(String profileName) {
    this.profileName = profileName;
  }

  /** cron 表达式（Spring 六字段）。 */
  public String getCron() {
    return cron;
  }

  /** cron 表达式（Spring 六字段）。 */
  public void setCron(String cron) {
    this.cron = cron;
  }

  /** 时区（空 = 运行时回退系统时区）。 */
  public String getZone() {
    return zone;
  }

  /** 时区（空 = 运行时回退系统时区）。 */
  public void setZone(String zone) {
    this.zone = zone;
  }

  /** 到点发给 Agent 的消息。 */
  public String getMessage() {
    return message;
  }

  /** 到点发给 Agent 的消息。 */
  public void setMessage(String message) {
    this.message = message;
  }

  /** 是否启用（新登记默认 true；reconcile 不更新此列）。 */
  public boolean isEnabled() {
    return enabled;
  }

  /** 是否启用。 */
  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  /** 下次触发时刻（非法配置为 null——research D9）。 */
  public LocalDateTime getNextRunAt() {
    return nextRunAt;
  }

  /** 下次触发时刻。 */
  public void setNextRunAt(LocalDateTime nextRunAt) {
    this.nextRunAt = nextRunAt;
  }

  /** 上次触发时刻。 */
  public LocalDateTime getLastRunAt() {
    return lastRunAt;
  }

  /** 上次触发时刻。 */
  public void setLastRunAt(LocalDateTime lastRunAt) {
    this.lastRunAt = lastRunAt;
  }

  /** 上次结果：success / failed 字面。 */
  public String getLastStatus() {
    return lastStatus;
  }

  /** 上次结果。 */
  public void setLastStatus(String lastStatus) {
    this.lastStatus = lastStatus;
  }

  /** 累计触发次数（reconcile 不更新此列——重启不丢）。 */
  public long getRunCount() {
    return runCount;
  }

  /** 累计触发次数。 */
  public void setRunCount(long runCount) {
    this.runCount = runCount;
  }

  /** 状态更新时间。 */
  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  /** 状态更新时间。 */
  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
