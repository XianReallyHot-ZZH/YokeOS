package com.yokeos.core.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.SessionManager;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

/**
 * 课件《第29节》验收 harness：AgentSchedulerUnregisterTest——注销语义四守点：cancel(false) 不打断执行中（坑⑥：只移句柄不 cancel =
 * 僵尸定时）、taskId 与注册侧同源派生（坑⑤：两处派生漂移则注销找不到句柄）、无 schedules 空跑、重复注销静默。 注册侧语义 25 节 AgentSchedulerTest 已钉
 * 11 用例，本类不重做。
 */
class AgentSchedulerUnregisterTest {

  private static final String CRON = "0 0 9 * * *";

  private static final String ZONE = "Asia/Shanghai";

  private TaskScheduler taskScheduler;

  private AgentScheduler scheduler;

  @BeforeEach
  void setUp() {
    taskScheduler = mock(TaskScheduler.class);
    ScheduledFuture<?> future = mock(ScheduledFuture.class);
    // ScheduledFuture<?> 通配符：doReturn 避开 thenReturn 的类型捕获问题（25 节参照钉版树同款）
    doReturn(future).when(taskScheduler).schedule(any(Runnable.class), any(Trigger.class));
    this.future = future;
    scheduler =
        new AgentScheduler(
            taskScheduler,
            mock(ProfileRegistry.class),
            mock(AgentService.class),
            mock(SessionManager.class),
            mock(ScheduledTaskStore.class));
  }

  private ScheduledFuture<?> future;

  private static Profile.ScheduleConfig schedule(String id) {
    return new Profile.ScheduleConfig(id, CRON, ZONE, "到点了");
  }

  private static Profile profileWithSchedules(String name, Profile.ScheduleConfig... schedules) {
    return new Profile(
        name,
        "描述",
        new Profile.Identity(name, "人格提示"),
        new Profile.ProviderConfig("deepseek", "deepseek-chat", null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(schedules),
        List.of(),
        Profile.Settings.DEFAULT);
  }

  @Test
  @DisplayName("注销后句柄cancel且不打断执行中_cancel传false")
  void unregisterCancelsHandleWithoutInterrupt() {
    Profile profile = profileWithSchedules("ops", schedule("morning"));
    scheduler.registerProfile(profile);

    scheduler.unregisterProfile(profile);

    verify(future).cancel(false); // 坑⑥：只移句柄不 cancel = 定时变僵尸；false = 不打断执行中（跑完落账）
  }

  @Test
  @DisplayName("taskId与注册侧同源派生_跨Agent同id互不误伤_多条定时逐条注销")
  void taskIdSharesDerivationWithRegister() {
    // 两个 Agent 同写 id: daily——taskId 前缀隔离（25 节修正案），注销 A 不得碰 B 的句柄
    Profile agentA = profileWithSchedules("agent-a", schedule("daily"));
    Profile agentB = profileWithSchedules("agent-b", schedule("daily"));
    Profile multi = profileWithSchedules("multi", schedule("morning"), schedule("evening"));
    scheduler.registerProfile(agentA);
    scheduler.registerProfile(agentB);
    scheduler.registerProfile(multi);

    scheduler.unregisterProfile(agentA);

    // A 的句柄被注销；B 与 multi 的句柄原封不动（同 id 不误伤、逐条语义）
    verify(future).cancel(false); // doReturn 固定返回同一 future，注销 A 恰 cancel 一次
    org.mockito.Mockito.reset(future);
    scheduler.unregisterProfile(multi);
    verify(future, org.mockito.Mockito.times(2)).cancel(false); // multi 两条定时逐条注销
    org.mockito.Mockito.reset(future);
    scheduler.unregisterProfile(agentB);
    verify(future).cancel(false);
  }

  @Test
  @DisplayName("无schedules的Profile注销空跑不报错")
  void unregisterWithoutSchedulesIsNoOp() {
    Profile plain =
        new Profile(
            "plain",
            "描述",
            new Profile.Identity("plain", "人格提示"),
            new Profile.ProviderConfig("deepseek", "deepseek-chat", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            Profile.Settings.DEFAULT);

    assertDoesNotThrow(() -> scheduler.unregisterProfile(plain));
    verify(taskScheduler, never()).schedule(any(Runnable.class), any(Trigger.class));
  }

  @Test
  @DisplayName("重复注销静默_第二次不再cancel不抛异常")
  void doubleUnregisterStaysSilent() {
    Profile profile = profileWithSchedules("ops", schedule("morning"));
    scheduler.registerProfile(profile);
    scheduler.unregisterProfile(profile);

    assertDoesNotThrow(() -> scheduler.unregisterProfile(profile)); // 句柄已移除：静默无操作

    verify(future, org.mockito.Mockito.times(1)).cancel(false); // 只 cancel 一次，第二次不重复
  }
}
