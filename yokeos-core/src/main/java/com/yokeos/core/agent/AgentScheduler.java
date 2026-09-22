package com.yokeos.core.agent;

import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.Session;
import com.yokeos.core.session.SessionManager;
import java.lang.invoke.MethodHandles;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * 定时任务——第三触发源「钟推」（技 §8.5）。CLI/Web 是人推，本类到点自己拼一条消息交给 {@link
 * AgentService#process}——走跟人推完全一样的入口，ReAct/Tool/Provider 一个字不用改。
 *
 * <p>只干一件事：到点了拼消息、交给编排入口。消息说什么是 Agent（AGENT.md）的事，交上去怎么处理是 ReActLoop 的事，都不归本类管——职责划窄，不膨胀成工作流引擎。
 *
 * <p>四个坑的解法：坑一配置驱动（{@code TaskScheduler.schedule(...)} 动态注册，不用编译期写死的 {@code @Scheduled}）；坑二重叠跳过（按任务
 * id 的进程内 {@link ReentrantLock} + {@code tryLock}，第一阶段单实例、非分布式锁）； 坑三失败隔离（单次失败只记日志不外抛、审计走 {@code
 * process} 既有链路、finally 必放锁、执行留痕自身失败也不外抛）； 坑四时区显式（{@link CronTrigger} 带 {@link
 * ZoneId}，不由服务器系统时区替用户做主）。
 *
 * <p>任务标识 {@code {profileName}:{作者声明的 id}}（25 节修正案：id 由 frontmatter 声明，必填 + profile
 * 内唯一——AgentLoader 校验；绑定不随声明顺序漂移，序号派生在调序/删插时会错位嫁接执行历史；profile 名前缀防跨 Agent 撞名）。锁维度、表主键、句柄 key
 * 共用此标识。会话三元组固定 {@code (scheduler, scheduler, profileName)}，session_id 由 {@link SessionManager} 内部经
 * SessionIds 单点拼接，本类不生成。
 *
 * <p>纯 POJO：装配与启动注册由 YokeosRuntime 显式做（{@code @Bean(initMethod="registerAll")})，core 类不放 Spring
 * 注解（与 AgentService/ReActLoop 同构）。
 */
@edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
    value = {"EI_EXPOSE_REP", "EI_EXPOSE_REP2"},
    justification =
        "taskStore 等协作者是注入的单例服务，构造注入共享同一引用正是意图；lockFor 返回共享锁是有意为之"
            + "（harness 占锁模拟「上一次还在跑」），无法也不应防御性拷贝。")
public final class AgentScheduler {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  /** 钟推的会话身份三元组固定值：同一 Profile 的历次定时触发复用同一 Session（技 §8.5、FR-006）。 */
  private static final String SCHEDULER_CHANNEL = "scheduler";

  private static final String SCHEDULER_USER = "scheduler";

  private final TaskScheduler taskScheduler;

  private final ProfileRegistry profileRegistry;

  private final AgentService agentService;

  private final SessionManager sessionManager;

  /** 任务状态 + 执行历史落 SQLite（重启不丢），并支撑启停。 */
  private final ScheduledTaskStore taskStore;

  /** 任务 id → 锁：防同一任务重叠执行。进程内锁，第一阶段单实例足够（非分布式锁）。 */
  private final ConcurrentMap<String, Lock> taskLocks = new ConcurrentHashMap<>();

  /** 任务 id → 可注销句柄：为 29/30 节运行时注销/更新 Agent 铺路（本节只登记、留句柄）。 */
  private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

  /** 五协作者注入：调度池、注册表、编排入口、会话管理与任务存储。 */
  public AgentScheduler(
      TaskScheduler taskScheduler,
      ProfileRegistry profileRegistry,
      AgentService agentService,
      SessionManager sessionManager,
      ScheduledTaskStore taskStore) {
    this.taskScheduler = taskScheduler;
    this.profileRegistry = profileRegistry;
    this.agentService = agentService;
    this.sessionManager = sessionManager;
    this.taskStore = taskStore;
  }

  /** 启动时扫一遍所有 Agent 的 schedules 逐个 Profile 注册（配置驱动，坑一；装配 initMethod 调用）。 */
  public void registerAll() {
    for (Profile profile : profileRegistry.all()) {
      registerProfile(profile);
    }
  }

  /**
   * 注销该 Agent 的全部定时（29 节运行时原语，30 节 DELETE/PUT 消费）：按与注册侧同一 {@link #taskIdOf} 派生找句柄，
   * cancel(false)——只取消后续排期、不打断正在执行的那次（跑完落账保审计完整）——并移除句柄。 句柄不存在（重复注销/从未注册）静默无操作；无 schedules
   * 空跑不报错。不联动 ProfileRegistry、不写 scheduled_tasks 表——「先注销定时再移出索引再归档」的编排归 30 节 AgentLifecycleService。
   */
  public void unregisterProfile(Profile profile) {
    for (Profile.ScheduleConfig sc : profile.schedules()) {
      String taskId = taskIdOf(profile, sc);
      ScheduledFuture<?> future = scheduledTasks.remove(taskId);
      if (future != null) {
        future.cancel(false); // 不打断执行中（research D5）
      }
    }
  }

  /**
   * 注册单个 Agent 的全部定时：逐条 {@code CronTrigger(cron, zone)} 动态注册并留可注销句柄，同时 reconcile 登记进
   * SQLite（重启后可查）。单条 cron/时区非法只跳过这条、不拖垮其它（坑六，FR-007）。
   */
  public void registerProfile(Profile profile) {
    for (Profile.ScheduleConfig sc : profile.schedules()) {
      try {
        String taskId = taskIdOf(profile, sc);
        ScheduledFuture<?> future =
            taskScheduler.schedule(
                () -> runOnce(profile, sc), new CronTrigger(sc.cron(), resolveZone(sc.zone())));
        if (future != null) {
          scheduledTasks.put(taskId, future); // 留可注销句柄（29/30 节用）
        }
        taskStore.reconcile(
            taskId, profile.name(), sc.cron(), sc.zone(), sc.message(), nextExecution(sc));
        log.info("定时任务注册完成（任务定义与下次触发见 scheduled_tasks 表）");
      } catch (RuntimeException e) {
        // 消息编译期常量，任务 id 与 cron 进异常消息（CRLF 门禁——AgentLoader 同款形态）
        log.warn(
            "跳过非法定时任务（id/cron/zone 见异常消息）",
            new IllegalArgumentException(
                "taskId=" + taskIdOf(profile, sc) + ", cron=" + sc.cron() + ", zone=" + sc.zone(),
                e));
      }
    }
  }

  /** 定时触发入口：先看启用状态（停用则跳过、不记执行——停用语义区别于失败），启用才真正执行。 */
  public void runOnce(Profile profile, Profile.ScheduleConfig sc) {
    if (!taskStore.isEnabled(taskIdOf(profile, sc))) {
      log.info("定时任务已停用，本次触发跳过（任务见 scheduled_tasks 表）");
      return;
    }
    execute(profile, sc);
  }

  /**
   * 人推补跑入口（FR-012）：按任务 id 找到任务手动跑一次（无视启用状态，属显式手动触发）——31 节 Demo 补跑与验收 harness 确定性驱动共用；第一阶段不挂
   * REST（调度管理端点列扩展规划位，ADR 0008）。找不到抛 {@link IllegalArgumentException}。
   */
  public void runNow(String taskId) {
    for (Profile profile : profileRegistry.all()) {
      for (Profile.ScheduleConfig sc : profile.schedules()) {
        if (taskIdOf(profile, sc).equals(taskId)) {
          execute(profile, sc);
          return;
        }
      }
    }
    throw new IllegalArgumentException("定时任务不存在: " + taskId);
  }

  /**
   * 真正跑一次：拿锁 → 拼消息交给编排入口 → 成功失败都 recordExecution 留痕并更新任务状态 → finally 放锁。
   * 拿不到锁说明上一次还没跑完，直接跳过本次（坑二：不排队、不并行两份）。public 为可测（harness 直接调）。
   */
  public void execute(Profile profile, Profile.ScheduleConfig sc) {
    String taskId = taskIdOf(profile, sc);
    Lock lock = lockFor(taskId);
    if (!lock.tryLock()) {
      log.info("定时任务上一次还在跑，跳过本次触发（任务见 scheduled_tasks 表）"); // 坑二
      return;
    }
    Instant startedAt = Instant.now();
    long start = System.currentTimeMillis();
    String sessionId = null;
    boolean success = false;
    String error = null;
    try {
      // channel/user 固定 scheduler：同一 Profile 历次触发复用同一 Session，历史靠 max_history_turns 截断兜底（18 节三元组）
      Session session =
          sessionManager.getOrCreate(SCHEDULER_CHANNEL, SCHEDULER_USER, profile.name());
      sessionId = session.sessionId();
      agentService.process(session, sc.message()); // 人推同款入口；审计在 process 内部（坑三前半）
      success = true;
    } catch (Exception e) {
      // 坑三：一次失败只记日志、不外抛，不把调度器搞挂、不影响其它任务的下次触发
      error = e.getMessage();
      log.error("定时任务执行失败（任务见异常消息）", new IllegalStateException("taskId=" + taskId, e));
    } finally {
      lock.unlock(); // 成功失败都必须放锁，否则这个任务永远卡住（坑三中段）
      try {
        // 留痕与审计两表同源：成功失败都记（坑三后半）；落库自身失败也只记日志不外抛
        taskStore.recordExecution(
            taskId,
            sessionId,
            startedAt,
            success,
            error,
            System.currentTimeMillis() - start,
            nextExecution(sc));
      } catch (RuntimeException re) {
        log.warn("定时任务执行记录落库失败（任务见异常消息）", new IllegalStateException("taskId=" + taskId, re));
      }
    }
  }

  /** 按任务 id 取同一把锁。public 为可测（harness 占锁模拟「上一次还在跑」）。 */
  public Lock lockFor(String taskId) {
    return taskLocks.computeIfAbsent(taskId, id -> new ReentrantLock());
  }

  /**
   * 任务标识：{@code "{profileName}:{作者声明的 id}"}——绑定源是作者 id（编辑顺序无关），前缀只做跨 Agent 命名空间隔离 （两个 Agent 同写
   * {@code id: daily} 不互撞表行/锁/句柄）。
   */
  static String taskIdOf(Profile profile, Profile.ScheduleConfig sc) {
    return profile.name() + ":" + sc.id();
  }

  /** 按 cron/zone 算下次触发时刻；非法配置返回 null（不影响执行本身——research D9）。 */
  private Instant nextExecution(Profile.ScheduleConfig sc) {
    try {
      return new CronTrigger(sc.cron(), resolveZone(sc.zone()))
          .nextExecution(new SimpleTriggerContext());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 时区显式（坑四）：空/blank 时才退回服务器系统时区，否则按配置解析。 */
  private ZoneId resolveZone(String zone) {
    return zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone);
  }
}
