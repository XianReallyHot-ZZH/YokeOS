package com.yokeos.storage;

import com.yokeos.core.agent.ScheduledTaskStore;
import com.yokeos.core.agent.ScheduledTaskView;
import com.yokeos.core.agent.TaskExecutionView;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.data.domain.PageRequest;

/**
 * {@link ScheduledTaskStore} 的 JPA 实现（第 25 节）：任务状态入 scheduled_tasks、执行历史入 task_executions。
 *
 * <p>reconcile 幂等 upsert（已存在则保留 enabled 与 run_count——重启不丢运行状态，坑十一）；recordExecution 先写
 * 一条历史再更新任务行（与审计两表同源的留痕纪律）；isEnabled 未登记按启用处理（fail-open）。契约时间用 {@link Instant}、既有表列用 {@link
 * LocalDateTime}（18 节起惯例）——边界转换在本类收口（系统时区）。
 */
public class JpaScheduledTaskStore implements ScheduledTaskStore {

  private final ScheduledTaskRepository tasks;

  private final TaskExecutionRepository executions;

  /** 两仓库注入：任务状态表与执行历史表。 */
  public JpaScheduledTaskStore(ScheduledTaskRepository tasks, TaskExecutionRepository executions) {
    this.tasks = tasks;
    this.executions = executions;
  }

  @Override
  public void reconcile(
      String taskId,
      String profileName,
      String cron,
      String zone,
      String message,
      Instant nextRunAt) {
    ScheduledTask task = tasks.findById(taskId).orElse(null);
    if (task == null) {
      task = new ScheduledTask();
      task.setTaskId(taskId);
      task.setEnabled(true); // 新任务默认启用
      task.setRunCount(0);
    }
    // 只更新定义字段——enabled 与 runCount 保留原值（重启不丢）
    task.setProfileName(profileName);
    task.setCron(cron);
    task.setZone(zone);
    task.setMessage(message);
    task.setNextRunAt(toLocal(nextRunAt));
    task.setUpdatedAt(LocalDateTime.now());
    tasks.save(task);
  }

  @Override
  public void recordExecution(
      String taskId,
      String sessionId,
      Instant startedAt,
      boolean success,
      String errorMessage,
      long durationMs,
      Instant nextRunAt) {
    TaskExecution execution = new TaskExecution();
    execution.setTaskId(taskId);
    execution.setSessionId(sessionId);
    execution.setStartedAt(toLocal(startedAt));
    execution.setSuccess(success);
    execution.setErrorMessage(errorMessage);
    execution.setDurationMs(durationMs);
    executions.save(execution);

    tasks
        .findById(taskId)
        .ifPresent(
            task -> {
              task.setLastRunAt(toLocal(startedAt));
              task.setLastStatus(success ? "success" : "failed");
              task.setRunCount(task.getRunCount() + 1);
              task.setNextRunAt(toLocal(nextRunAt));
              task.setUpdatedAt(LocalDateTime.now());
              tasks.save(task);
            });
  }

  @Override
  public boolean isEnabled(String taskId) {
    return tasks.findById(taskId).map(ScheduledTask::isEnabled).orElse(true); // fail-open
  }

  @Override
  public void setEnabled(String taskId, boolean enabled) {
    ScheduledTask task =
        tasks
            .findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("定时任务不存在: " + taskId));
    task.setEnabled(enabled);
    task.setUpdatedAt(LocalDateTime.now());
    tasks.save(task);
  }

  @Override
  public List<ScheduledTaskView> list() {
    return tasks.findAll().stream().map(JpaScheduledTaskStore::toView).toList();
  }

  @Override
  public List<TaskExecutionView> executions(String taskId, int limit) {
    return executions.findByTaskIdOrderByStartedAtDesc(taskId, PageRequest.of(0, limit)).stream()
        .map(JpaScheduledTaskStore::toView)
        .toList();
  }

  private static ScheduledTaskView toView(ScheduledTask task) {
    return new ScheduledTaskView(
        task.getTaskId(),
        task.getProfileName(),
        task.getCron(),
        task.getZone(),
        task.getMessage(),
        task.isEnabled(),
        toInstant(task.getNextRunAt()),
        toInstant(task.getLastRunAt()),
        task.getLastStatus(),
        task.getRunCount(),
        toInstant(task.getUpdatedAt()));
  }

  private static TaskExecutionView toView(TaskExecution execution) {
    return new TaskExecutionView(
        execution.getId() == null ? 0L : execution.getId(),
        execution.getTaskId(),
        execution.getSessionId(),
        toInstant(execution.getStartedAt()),
        execution.isSuccess(),
        execution.getErrorMessage(),
        execution.getDurationMs());
  }

  /** Instant → 表列 LocalDateTime（系统时区，18 节起既有表惯例）。 */
  private static LocalDateTime toLocal(Instant instant) {
    return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
  }

  /** 表列 LocalDateTime → Instant（{@code toInstant} 只收 ZoneOffset，经 atZone 走系统时区）。 */
  private static Instant toInstant(LocalDateTime localDateTime) {
    return localDateTime == null ? null : localDateTime.atZone(ZoneId.systemDefault()).toInstant();
  }
}
