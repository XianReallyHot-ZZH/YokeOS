# Contract: 调度器与任务存储（第25节）

**Phase 1 产出**。本节无对外 REST 端点（ADR 0008 显式偏差）；契约面是两个 core 类的公开方法。签名以 1.1.8/6.2.8 本地依赖 javap 核实为准（research D1）。

## ScheduledTaskStore（跨模块契约，core 定义 / storage JPA 实现）

```java
package com.yokeos.core.agent;

public interface ScheduledTaskStore {
  /** 幂等登记/更新：已存在则只更新定义字段与 next_run_at/updated_at，保留 enabled 与 run_count
   *  （重启不丢）；不存在则插入（enabled=true、run_count=0）。 */
  void reconcile(String taskId, String profileName, String cron, String zone,
                 String message, java.time.Instant nextRunAt);

  /** 记一次执行（成功失败都记）并更新任务状态（last_run/last_status/run_count+1/next_run_at）。
   *  sessionId 可空（process 抛在拿 Session 之前）。 */
  void recordExecution(String taskId, String sessionId, java.time.Instant startedAt,
                       boolean success, String errorMessage, long durationMs,
                       java.time.Instant nextRunAt);

  /** 未登记按启用处理（fail-open）——登记滞后不该让任务漏跑。 */
  boolean isEnabled(String taskId);

  /** 启用/停用（第一阶段无调用方，契约一次立全；测试与管理台扩展期用）。 */
  void setEnabled(String taskId, boolean enabled);

  /** 全部任务状态视图（30 节管理端点复用）。 */
  java.util.List<ScheduledTaskView> list();

  /** 某任务最近 limit 条执行历史（started_at 倒序）。 */
  java.util.List<TaskExecutionView> executions(String taskId, int limit);
}
```

## AgentScheduler（core 纯 POJO，YokeosRuntime 装配）

```java
package com.yokeos.core.agent;

public class AgentScheduler {
  /** 构造注入五协作者：TaskScheduler / ProfileRegistry / AgentService / SessionManager / ScheduledTaskStore */

  /** 启动扫描：全部 Profile 逐个 registerProfile（装配 initMethod 调用）。 */
  public void registerAll()

  /** 单 Profile 注册：逐条 sc → CronTrigger(cron, resolveZone(zone)) → schedule + 句柄入表
   *  + taskStore.reconcile；单条 RuntimeException catch 记日志跳过（FR-007）。 */
  public void registerProfile(Profile profile)

  /** 定时触发入口：isEnabled 为 false 跳过（不记执行）；为 true → execute。 */
  public void runOnce(Profile profile, Profile.ScheduleConfig sc)

  /** 人推补跑：按 taskId 遍历注册表，无视启用状态 execute；找不到抛 IllegalArgumentException（FR-12）。 */
  public void runNow(String taskId)

  /** 真跑一次：tryLock 失败跳过（不排队不并行）→ getOrCreate("scheduler","scheduler",name)
   *  → process(session, message) → finally：unlock + recordExecution（自身失败只记日志，FR-005）。
   *  public 为可测（harness 直调）。 */
  public void execute(Profile profile, Profile.ScheduleConfig sc)

  /** 按任务 id 取同一把锁；public 为可测（harness 占锁模拟「上一次还在跑」）。 */
  public java.util.concurrent.locks.Lock lockFor(String taskId)

  /** 派生任务标识："{profileName}#{声明序号}"（序号 = sc 在 profile.schedules() 中的位置 + 1，拍板①）。 */
  static String taskIdOf(Profile profile, Profile.ScheduleConfig sc)
}
```

**行为契约（不变量）**：

1. `execute` 成功路径恰好产生：1 次 `process` + 1 条 `recordExecution(success=true)`；失败路径：0 次外抛 + 1 条 `recordExecution(success=false, error)` + 锁已释放（再触发能进入）。
2. 锁未释放路径不存在——`unlock` 在 `finally` 第一句（`recordExecution` 的 try-catch 包在其后，落库失败不影响放锁）。
3. 会话三元组恒为 `("scheduler", "scheduler", profile.name())`；session_id 由 `SessionManager` 内部拼接，本类不生成。
4. 调度器不感知消息语义、不碰 ReActLoop、不为钟推单开审计——审计发生在 `process` 内部既有链路。

## 装配契约（yokeos-cli YokeosRuntime，三 Bean）

```java
@Bean ThreadPoolTaskScheduler taskScheduler()
// poolSize=2、threadNamePrefix "yokeos-sched-"、setDaemon(true)（chat 跑完 JVM 可退）、initialize()

@Bean ScheduledTaskStore scheduledTaskStore(ScheduledTaskRepository tasks,
                                            TaskExecutionRepository executions)
// → new JpaScheduledTaskStore(tasks, executions)

@Bean(initMethod = "registerAll") AgentScheduler agentScheduler(...)
// 依赖注入完成后执行 registerAll——ProfileRegistry Bean 已构造并填充（16 节装配序）
```

**ServeCommand/GatewayCommand**：骨架注释兑现（「定时任务随 serve/gateway 常驻调度归 25 节」→ 已兑现说明 + 启动文案提及调度常驻）。

## 显式不做（契约边界）

- 无 REST 端点（`runNow` 是类方法，REST 化放扩展阶段——ADR 0008）。
- 无 `unregisterProfile`（句柄表留位，注销方法归 29 节——research D7）。
- 无分布式锁/选主；无失败重试/告警；无 cron 高级语法。
