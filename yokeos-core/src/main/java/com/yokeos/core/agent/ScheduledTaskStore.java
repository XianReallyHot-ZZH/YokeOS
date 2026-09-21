package com.yokeos.core.agent;

import java.time.Instant;
import java.util.List;

/**
 * 定时任务的持久化契约（技 §8.5）：把任务状态与执行历史落 SQLite，重启后仍在。
 *
 * <p>依赖倒置：接口在 core（{@link AgentScheduler} 用它），JPA 实现在 yokeos-storage。定义来源仍是 AGENT.md frontmatter 的
 * {@code schedules}——本 store 只存「状态 + 历史」，不作为定义源（重启时从文件重新协调）。
 */
public interface ScheduledTaskStore {

  /**
   * 幂等登记/更新一条任务的登记信息与下次触发（启动扫描时调用）：已存在则只更新定义字段（cron/zone/message）与 next_run_at/updated_at，保留
   * enabled 与 run_count（重启不丢运行状态）；不存在则插入（默认启用、run_count=0）。
   */
  void reconcile(
      String taskId,
      String profileName,
      String cron,
      String zone,
      String message,
      Instant nextRunAt);

  /**
   * 记录一次执行（成功失败都记），并更新任务的 last_run_at/last_status/run_count/next_run_at。
   *
   * @param sessionId 本次触发所用的钟推会话标识；执行抛在拿到会话之前时为 null
   */
  void recordExecution(
      String taskId,
      String sessionId,
      Instant startedAt,
      boolean success,
      String errorMessage,
      long durationMs,
      Instant nextRunAt);

  /** 任务是否启用：未登记的按启用处理（fail-open——登记滞后不该让任务漏跑）。 */
  boolean isEnabled(String taskId);

  /** 启用/停用一条任务（第一阶段无调用方，契约一次立全；管理端点归扩展阶段）。 */
  void setEnabled(String taskId, boolean enabled);

  /** 全部定时任务的状态视图（30 节管理端点与排障复用）。 */
  List<ScheduledTaskView> list();

  /** 某任务最近 {@code limit} 条执行历史（按开始时间倒序）。 */
  List<TaskExecutionView> executions(String taskId, int limit);
}
