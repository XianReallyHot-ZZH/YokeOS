package com.yokeos.core.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.Profile.ScheduleConfig;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.Session;
import com.yokeos.core.session.SessionIds;
import com.yokeos.core.session.SessionManager;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * 课件《第25节》验收 harness：AgentSchedulerTest——一个类覆盖四个坑。诀窍是别真等时间：runOnce 是独立方法直接调， 行为逻辑全可测；cron 触发本身是
 * Spring 的事，只验「注册参数传对了」。
 *
 * <p>setUp 钉死 {@code isEnabled=true}（坑八：Mockito boolean 缺省 false，不钉则 runOnce 全被「停用」跳过、测试白绿）；
 * 时区断言不依赖 {@code CronTrigger.equals}（坑九：其只比 cron 不比时区）——固定 TriggerContext 下比 nextExecution 时刻。
 */
class AgentSchedulerTest {

  private static final String PROFILE_NAME = "ops-agent";
  private static final String CRON = "0 0 9 * * *";
  private static final String ZONE = "Asia/Shanghai";

  /** 固定触发上下文时刻：nextExecution 断言的确定性锚点（不取「现在」，杜绝跨秒/跨分钟边界 flaky）。 */
  private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

  private TaskScheduler taskScheduler;
  private ProfileRegistry profileRegistry;
  private AgentService agentService;
  private SessionManager sessionManager;
  private ScheduledTaskStore taskStore;
  private AgentScheduler scheduler;

  @BeforeEach
  void setUp() {
    taskScheduler = mock(TaskScheduler.class);
    profileRegistry = mock(ProfileRegistry.class);
    agentService = mock(AgentService.class);
    sessionManager = mock(SessionManager.class);
    taskStore = mock(ScheduledTaskStore.class);
    when(taskStore.isEnabled(any())).thenReturn(true); // 坑八：钉死缺省启用
    scheduler =
        new AgentScheduler(taskScheduler, profileRegistry, agentService, sessionManager, taskStore);
  }

  private static ScheduleConfig sc(String cron, String zone, String message) {
    return new ScheduleConfig(cron, zone, message);
  }

  private static Profile profileNamed(String name, ScheduleConfig... schedules) {
    return new Profile(
        name, null, null, null, null, null, null, null, null, List.of(schedules), null, null);
  }

  private Session stubSession(String profileName) {
    return new Session(SessionIds.compose("scheduler", "scheduler", profileName), profileName);
  }

  @Test
  @DisplayName("注册时CronTrigger带上配置的cron和时区（时区以固定上下文下次触发时刻证明）")
  void registerPassesCronAndZoneToTrigger() {
    when(profileRegistry.all())
        .thenReturn(List.of(profileNamed(PROFILE_NAME, sc(CRON, ZONE, "跑"))));

    scheduler.registerAll();

    ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
    verify(taskScheduler).schedule(any(Runnable.class), captor.capture());
    CronTrigger trigger = assertInstanceOf(CronTrigger.class, captor.getValue());
    assertEquals(CRON, trigger.getExpression(), "cron 表达式必须来自配置");
    // 坑九：equals 只比 cron 不比时区——固定 TriggerContext 下比下次触发时刻
    SimpleTriggerContext fixed = new SimpleTriggerContext(FIXED_NOW, FIXED_NOW, FIXED_NOW);
    Instant actual = trigger.nextExecution(fixed);
    assertEquals(
        new CronTrigger(CRON, ZoneId.of(ZONE)).nextExecution(fixed),
        actual,
        "时区必须来自配置（与同 cron + 配置时区参照一致）");
    assertNotEquals(
        new CronTrigger(CRON, ZoneId.of("UTC")).nextExecution(fixed),
        actual,
        "时区不得被别的时区顶掉（与 UTC 参照不一致）");
  }

  @Test
  @DisplayName("zone缺省或空白时回退服务器系统时区（analyze C2）")
  void registerFallsBackToSystemZoneWhenZoneBlank() {
    when(profileRegistry.all())
        .thenReturn(List.of(profileNamed(PROFILE_NAME, sc(CRON, null, "跑"))));

    scheduler.registerAll();

    ArgumentCaptor<Trigger> captor = ArgumentCaptor.forClass(Trigger.class);
    verify(taskScheduler).schedule(any(Runnable.class), captor.capture());
    SimpleTriggerContext fixed = new SimpleTriggerContext(FIXED_NOW, FIXED_NOW, FIXED_NOW);
    assertEquals(
        new CronTrigger(CRON, ZoneId.systemDefault()).nextExecution(fixed),
        captor.getValue().nextExecution(fixed),
        "空 zone 应回退系统时区");
  }

  @Test
  @DisplayName("钟推会话三元组固定scheduler/scheduler/Agent名且两次触发同一Session")
  void runOnceUsesFixedSchedulerSessionTriple() {
    Profile profile = profileNamed(PROFILE_NAME, sc(CRON, ZONE, "汇总昨天的进度"));
    Session session = stubSession(PROFILE_NAME);
    when(sessionManager.getOrCreate("scheduler", "scheduler", PROFILE_NAME)).thenReturn(session);

    scheduler.runOnce(profile, profile.schedules().get(0));
    scheduler.runOnce(profile, profile.schedules().get(0));

    verify(sessionManager, times(2)).getOrCreate("scheduler", "scheduler", PROFILE_NAME);
    verify(agentService, times(2)).process(session, "汇总昨天的进度");
    // 历次触发落同一 session：recordExecution 两笔的 sessionId 一致且为三元组拼接（拼接单点 SessionIds）
    ArgumentCaptor<String> sessionIds = ArgumentCaptor.forClass(String.class);
    verify(taskStore, times(2))
        .recordExecution(
            any(),
            sessionIds.capture(),
            any(),
            anyBoolean(),
            any(),
            anyLong(),
            any()); // primitive 参数必须用 anyBoolean/anyLong（any() 返回 null 拆箱即炸）
    assertEquals(SessionIds.compose("scheduler", "scheduler", PROFILE_NAME), sessionIds.getValue());
    assertEquals(sessionIds.getAllValues().get(0), sessionIds.getAllValues().get(1));
  }

  @Test
  @DisplayName("派生taskId同profile按声明序号区分且跨profile前缀隔离（坑十）")
  void taskIdDerivedFromProfileNameAndDeclarationIndex() {
    Profile twoRules =
        profileNamed(PROFILE_NAME, sc(CRON, ZONE, "早报"), sc("0 0 18 * * *", ZONE, "晚报"));
    Profile another = profileNamed("night-agent", sc(CRON, ZONE, "夜巡"));

    scheduler.registerProfile(twoRules);
    scheduler.registerProfile(another);

    ArgumentCaptor<String> taskIds = ArgumentCaptor.forClass(String.class);
    verify(taskStore, times(3)).reconcile(taskIds.capture(), any(), any(), any(), any(), any());
    assertEquals(
        List.of(PROFILE_NAME + "#1", PROFILE_NAME + "#2", "night-agent#1"),
        taskIds.getAllValues(),
        "派生规则：{profileName}#{声明序号，从 1 起}");
  }

  @Test
  @DisplayName("runNow按taskId找到任务立即执行、找不到点名报错（人推补跑入口）")
  void runNowFindsTaskOrThrows() {
    Profile profile = profileNamed(PROFILE_NAME, sc(CRON, ZONE, "补跑一次"));
    when(profileRegistry.all()).thenReturn(List.of(profile));
    Session session = stubSession(PROFILE_NAME);
    when(sessionManager.getOrCreate(any(), any(), any())).thenReturn(session);

    scheduler.runNow(PROFILE_NAME + "#1");
    verify(agentService).process(session, "补跑一次");

    assertThrows(IllegalArgumentException.class, () -> scheduler.runNow("no-such-task"));
  }

  @Test
  @DisplayName("单条cron非法只跳过该条其它照常注册（坑六）")
  void invalidCronSkipsOnlyThatRule() {
    Profile profile =
        profileNamed(PROFILE_NAME, sc("not-a-cron", ZONE, "坏规则"), sc(CRON, ZONE, "好规则"));

    assertDoesNotThrow(() -> scheduler.registerProfile(profile));

    verify(taskScheduler, times(1)).schedule(any(Runnable.class), any(Trigger.class));
    ArgumentCaptor<String> taskIds = ArgumentCaptor.forClass(String.class);
    verify(taskStore, times(1)).reconcile(taskIds.capture(), any(), any(), any(), any(), any());
    assertEquals(PROFILE_NAME + "#2", taskIds.getValue(), "坏规则跳过、好规则照常登记");
  }

  @Test
  @DisplayName("注册时逐条reconcile登记且下次触发时刻可算出")
  void registerReconcilesEachScheduleWithNextRun() {
    when(profileRegistry.all())
        .thenReturn(List.of(profileNamed(PROFILE_NAME, sc(CRON, ZONE, "跑"))));

    scheduler.registerAll();

    verify(taskStore)
        .reconcile(
            eq(PROFILE_NAME + "#1"),
            eq(PROFILE_NAME),
            eq(CRON),
            eq(ZONE),
            eq("跑"),
            any(Instant.class)); // nextRunAt 非空即可（依当前时刻，无法钉精确值）
  }

  @Test
  @DisplayName("停用任务runOnce跳过且不记执行（analyze C1，停用≠失败）")
  void disabledTaskSkipsWithoutRecording() {
    Profile profile = profileNamed(PROFILE_NAME, sc(CRON, ZONE, "停用的任务"));
    when(taskStore.isEnabled(PROFILE_NAME + "#1")).thenReturn(false);

    scheduler.runOnce(profile, profile.schedules().get(0));

    verify(agentService, never()).process(any(), any());
    verify(taskStore, never())
        .recordExecution(any(), any(), any(), anyBoolean(), any(), anyLong(), any());
  }

  @Test
  @DisplayName("上一次还没跑完时本次触发直接跳过且跳过是一次性的（坑二）")
  void skipsWhenPreviousRunStillHoldingLock() throws InterruptedException {
    Profile profile = profileNamed(PROFILE_NAME, sc(CRON, ZONE, "汇总昨天的进度"));
    Session session = stubSession(PROFILE_NAME);
    when(sessionManager.getOrCreate(any(), any(), any())).thenReturn(session);
    java.util.concurrent.locks.Lock lock = scheduler.lockFor(PROFILE_NAME + "#1");
    // 真实重叠是跨线程的（调度线程池）：ReentrantLock 对同线程可重入，同线程占锁后 tryLock 必成功——
    // 必须让另一线程占着锁，runOnce 的 tryLock 才会真失败（参照钉版树同款双闩手法）。
    java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    Thread holder =
        new Thread(
            () -> {
              lock.lock();
              locked.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } finally {
                lock.unlock();
              }
            });
    holder.start();
    locked.await(); // 确保另一线程已占锁，模拟「上一次还在跑」

    try {
      scheduler.runOnce(profile, profile.schedules().get(0));
      verify(agentService, never()).process(any(), any()); // 没有叠加执行
    } finally {
      release.countDown();
      holder.join();
    }

    scheduler.runOnce(profile, profile.schedules().get(0)); // 上一次结束后再触发
    verify(agentService, times(1)).process(session, "汇总昨天的进度"); // 能正常进入（跳过一次性）
  }

  @Test
  @DisplayName("任务抛异常不外抛且锁必须被释放且失败留痕（坑三，二进宫断言）")
  void processFailureDoesNotPropagateAndReleasesLock() {
    Profile profile = profileNamed(PROFILE_NAME, sc(CRON, ZONE, "会挂的任务"));
    Session session = stubSession(PROFILE_NAME);
    when(sessionManager.getOrCreate(any(), any(), any())).thenReturn(session);
    when(agentService.process(any(), any())).thenThrow(new RuntimeException("boom"));

    assertDoesNotThrow(() -> scheduler.runOnce(profile, profile.schedules().get(0)));

    // 二进宫：再触发一次能进来——锁真的放了，没有永久卡死
    scheduler.runOnce(profile, profile.schedules().get(0));
    verify(agentService, times(2)).process(session, "会挂的任务");

    // 失败留痕：recordExecution 记了 success=false 与失败原因
    org.mockito.ArgumentCaptor<Boolean> success =
        org.mockito.ArgumentCaptor.forClass(Boolean.class);
    verify(taskStore, times(2))
        .recordExecution(any(), any(), any(), success.capture(), any(), anyLong(), any());
    assertEquals(false, success.getValue());
  }

  @Test
  @DisplayName("执行记录落库自身失败不外抛且锁照样释放（坑三兜底）")
  void recordExecutionFailureSwallowedAndLockReleased() {
    Profile profile = profileNamed(PROFILE_NAME, sc(CRON, ZONE, "留痕会挂的任务"));
    Session session = stubSession(PROFILE_NAME);
    when(sessionManager.getOrCreate(any(), any(), any())).thenReturn(session);
    org.mockito.Mockito.doThrow(new RuntimeException("db down"))
        .when(taskStore)
        .recordExecution(any(), any(), any(), anyBoolean(), any(), anyLong(), any());

    assertDoesNotThrow(() -> scheduler.runOnce(profile, profile.schedules().get(0)));

    scheduler.runOnce(profile, profile.schedules().get(0)); // 再触发能进（锁已放）
    verify(agentService, times(2)).process(session, "留痕会挂的任务");
  }

  @Test
  @DisplayName("无任何定时规则时registerAll空跑不报错（analyze L1）")
  void registerAllWithNoSchedulesDoesNotThrow() {
    when(profileRegistry.all()).thenReturn(List.of(profileNamed("bare-agent")));

    assertDoesNotThrow(scheduler::registerAll);

    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
    verify(taskStore, never()).reconcile(any(), any(), any(), any(), any(), any());
  }
}
