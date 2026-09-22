package com.yokeos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yokeos.core.profile.AgentLoader;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.provider.ProviderService;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * AgentLifecycleService 编排验收（第 30 节）：复杂度全在编排顺序与失败回滚——协作者全 mock， InOrder 钉顺序、异常注入钉回滚（教学文档第四部分分层规则）。
 */
class AgentLifecycleServiceTest {

  @Mock AgentLoader agentLoader;
  @Mock ProfileRegistry profileRegistry;
  @Mock AgentScheduler agentScheduler;
  @Mock AgentStore agentStore;
  @Mock ProviderService providerService;

  private AgentLifecycleService lifecycle;

  private final Path agentDir = Path.of("agents/demo");

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    lifecycle =
        new AgentLifecycleService(
            agentLoader,
            profileRegistry,
            agentScheduler,
            agentStore,
            providerService,
            "deepseek",
            "deepseek-chat",
            Set.of("deepseek"));
  }

  private static Profile profileOf(String name, Profile.ScheduleConfig... schedules) {
    return new Profile(
        name,
        "描述",
        null,
        new Profile.ProviderConfig("deepseek", "deepseek-chat", null),
        null,
        null,
        null,
        null,
        null,
        List.of(schedules),
        null,
        null);
  }

  @Test
  @DisplayName("register 带 schedules 的 Agent：注册进注册表并注册定时（与 API create 同一段代码）")
  void registerWithSchedulesRegistersTimer() {
    Profile profile =
        profileOf("demo", new Profile.ScheduleConfig("morning", "0 8 * * *", null, "早报"));
    when(agentLoader.deriveProfile(any(), any())).thenReturn(profile);

    Profile result = lifecycle.register(agentDir);

    assertSame(profile, result);
    verify(profileRegistry).register(profile);
    verify(agentScheduler).registerProfile(profile);
  }

  @Test
  @DisplayName("register 无 schedules：注册但不挂定时")
  void registerWithoutSchedulesSkipsTimer() {
    Profile profile = profileOf("demo");
    when(agentLoader.deriveProfile(any(), any())).thenReturn(profile);
    when(profileRegistry.get("demo")).thenReturn(Optional.empty());

    lifecycle.register(agentDir);

    verify(profileRegistry).register(profile);
    verify(agentScheduler, never()).registerProfile(any());
  }

  @Test
  @DisplayName("防重收口（FR-016）：同名已注册时先注销旧定时再注册，旧 cron 不与新 cron 并跑")
  void registerUnregistersOldTimerBeforeRegisteringSameName() {
    Profile oldProfile =
        profileOf("demo", new Profile.ScheduleConfig("morning", "0 8 * * *", null, "旧"));
    Profile newProfile =
        profileOf("demo", new Profile.ScheduleConfig("evening", "0 20 * * *", null, "新"));
    when(agentLoader.deriveProfile(any(), any())).thenReturn(newProfile);
    when(profileRegistry.get("demo")).thenReturn(Optional.of(oldProfile));

    lifecycle.register(agentDir);

    InOrder order = inOrder(agentScheduler, profileRegistry);
    order.verify(agentScheduler).unregisterProfile(oldProfile); // 先注销旧句柄（顺序错 = 双跑）
    order.verify(profileRegistry).register(newProfile);
    order.verify(agentScheduler).registerProfile(newProfile);
  }

  @Test
  @DisplayName("get/list 直通注册表，无私货")
  void getAndListDelegateToRegistry() {
    when(profileRegistry.get("demo")).thenReturn(Optional.empty());
    when(profileRegistry.all()).thenReturn(List.of());

    assertEquals(Optional.empty(), lifecycle.get("demo"));
    assertEquals(0, lifecycle.list().size());
    verify(profileRegistry).get("demo");
    verify(profileRegistry).all();
  }

  @Test
  @DisplayName("create 按序：写目录 → 派生注册（带 schedules 挂定时）")
  void createWritesThenRegistersInOrder() {
    Profile profile =
        profileOf("demo", new Profile.ScheduleConfig("morning", "0 8 * * *", null, "早报"));
    when(profileRegistry.exists("demo")).thenReturn(false);
    when(agentStore.write("demo", "markdown")).thenReturn(agentDir);
    when(agentLoader.deriveProfile(any(), any())).thenReturn(profile);

    lifecycle.create("demo", "markdown");

    InOrder order = inOrder(agentStore, profileRegistry, agentScheduler);
    order.verify(agentStore).write("demo", "markdown");
    order.verify(profileRegistry).register(profile);
    order.verify(agentScheduler).registerProfile(profile);
  }

  @Test
  @DisplayName("name 冲突第一步就拒，一个字节都不写（坑一上半）")
  void createNameConflictRejectedBeforeAnyWrite() {
    when(profileRegistry.exists("demo")).thenReturn(true);

    assertThrows(IllegalArgumentException.class, () -> lifecycle.create("demo", "markdown"));

    verify(agentStore, never()).write(any(), any());
    verify(agentLoader, never()).deriveProfile(any(), any());
  }

  @Test
  @DisplayName("注册失败_必须回滚已写的Agent目录_不留半个Agent（坑一下半：最值钱）")
  void createRegisterFailsRollsBackWrittenDir() {
    when(profileRegistry.exists("half")).thenReturn(false);
    when(agentStore.write("half", "bad")).thenReturn(agentDir);
    when(agentLoader.deriveProfile(any(), any()))
        .thenThrow(new IllegalArgumentException("provider 不存在: ghost"));

    assertThrows(IllegalArgumentException.class, () -> lifecycle.create("half", "bad"));

    verify(agentStore).delete(agentDir); // 已写的目录删回去
    verify(agentScheduler, never()).registerProfile(any()); // 定时根本没走到
  }

  @Test
  @DisplayName("删除必须先停定时_再动索引和目录（坑二：顺序反了窗口期 cron 空转）")
  void deleteUnregistersTimerBeforeTouchingRegistryAndDir() {
    Profile profile = profileOf("demo");
    when(profileRegistry.get("demo")).thenReturn(Optional.of(profile));

    lifecycle.delete("demo");

    InOrder order = inOrder(agentScheduler, profileRegistry, agentStore);
    order.verify(agentScheduler).unregisterProfile(profile);
    order.verify(profileRegistry).remove("demo");
    order.verify(agentStore).archive("demo");
  }

  @Test
  @DisplayName("删除不存在的 Agent 抛非法参数（web 层前置 404 判定，core 兜底）")
  void deleteMissingTargetThrows() {
    when(profileRegistry.get("ghost")).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> lifecycle.delete("ghost"));
    verify(agentStore, never()).archive(any());
  }

  @Test
  @DisplayName("update schedules 变更：先注销旧定时再注册新（坑三：不并跑）")
  void updateScheduleChangedUnregistersBeforeRegister() {
    Profile oldProfile =
        profileOf("demo", new Profile.ScheduleConfig("morning", "0 8 * * *", null, "旧"));
    Profile newProfile =
        profileOf("demo", new Profile.ScheduleConfig("evening", "0 20 * * *", null, "新"));
    when(profileRegistry.get("demo")).thenReturn(Optional.of(oldProfile));
    when(agentStore.write("demo", "markdown")).thenReturn(agentDir);
    when(agentLoader.deriveProfile(any(), any())).thenReturn(newProfile);

    Profile result = lifecycle.update("demo", "markdown");

    assertSame(newProfile, result);
    InOrder order = inOrder(agentScheduler, profileRegistry);
    order.verify(agentScheduler).unregisterProfile(oldProfile); // 先注销旧句柄
    order.verify(profileRegistry).register(newProfile);
    order.verify(agentScheduler).registerProfile(newProfile);
  }

  @Test
  @DisplayName("update 先 parse 校验后落盘（analyze H1）：非法定义不写盘、旧定义不破坏")
  void updateValidatesBeforeWrite() {
    Profile oldProfile = profileOf("demo");
    when(profileRegistry.get("demo")).thenReturn(Optional.of(oldProfile));
    org.mockito.Mockito.doThrow(new IllegalArgumentException("frontmatter 未闭合"))
        .when(agentLoader)
        .parse(any(), any(), any());

    assertThrows(IllegalArgumentException.class, () -> lifecycle.update("demo", "垃圾内容"));

    verify(agentStore, never()).write(any(), any()); // 不落盘 = 旧 AGENT.md 完好
    verify(profileRegistry, never()).register(any()); // 旧注册不动
  }

  @Test
  @DisplayName("update 不存在的 Agent 抛非法参数（web 层 404）")
  void updateMissingTargetThrows() {
    when(profileRegistry.get("ghost")).thenReturn(Optional.empty());

    assertThrows(IllegalArgumentException.class, () -> lifecycle.update("ghost", "markdown"));
    verify(agentStore, never()).write(any(), any());
  }

  @Test
  @DisplayName("unregisterByDir（Watcher 删除事件）：只注销移索引、不归档")
  void unregisterByDirSkipsArchive() {
    Profile profile = profileOf("demo");
    when(profileRegistry.get("demo")).thenReturn(Optional.of(profile));

    lifecycle.unregisterByDir(agentDir);

    verify(agentScheduler).unregisterProfile(profile);
    verify(profileRegistry).remove("demo");
    verify(agentStore, never()).archive(any()); // 目录已被手工删，无物可归档
  }

  @Test
  @DisplayName("unregisterByDir 未注册目录：静默无操作（幂等）")
  void unregisterByDirUnknownIsNoop() {
    when(profileRegistry.get("demo")).thenReturn(Optional.empty());

    lifecycle.unregisterByDir(agentDir);

    verify(agentScheduler, never()).unregisterProfile(any());
    verify(agentStore, never()).archive(any());
  }

  private static final String DRAFT =
      "---\nname: weather-daily\ndescription: 天气\nprovider:\n  name: deepseek\n"
          + "  model: deepseek-chat\ntools:\n  - http_get\n---\n每天早八查天气推群";

  @Test
  @DisplayName("generate 正常链：chat 恰调一次（sessionId 前缀 agent-generation）、草稿原样返回")
  void generateHappyPathChatsOnceAndReturnsDraft() {
    when(providerService.chat(any(), any(), any()))
        .thenReturn(new com.yokeos.core.provider.ProviderResponse(DRAFT, null));

    String result = lifecycle.generate("每天早上九点查北京天气");

    assertEquals(DRAFT, result);
    org.mockito.ArgumentCaptor<String> sessionIds =
        org.mockito.ArgumentCaptor.forClass(String.class);
    verify(providerService, times(1)).chat(sessionIds.capture(), any(), any());
    assertTrue(sessionIds.getValue().startsWith("agent-generation"), "审计关联键前缀（FR-003）");
  }

  @Test
  @DisplayName("generate 不落盘不注册（坑五核心断言：store 与 registry 零写入）")
  void generateTouchesNeitherStoreNorRegistry() {
    when(providerService.chat(any(), any(), any()))
        .thenReturn(new com.yokeos.core.provider.ProviderResponse(DRAFT, null));

    lifecycle.generate("一句话");

    verify(agentStore, never()).write(any(), any());
    verify(profileRegistry, never()).register(any());
    verify(agentScheduler, never()).registerProfile(any());
  }

  @Test
  @DisplayName("generate 剥 Markdown 代码围栏后再返回（模型多吐 ``` 不误报非法）")
  void generateStripsCodeFences() {
    when(providerService.chat(any(), any(), any()))
        .thenReturn(
            new com.yokeos.core.provider.ProviderResponse("```markdown\n" + DRAFT + "\n```", null));

    String result = lifecycle.generate("一句话");

    assertEquals(DRAFT, result);
  }

  @Test
  @DisplayName("generate LLM 产出非法：IllegalArgumentException 可读原因（→400）")
  void generateInvalidOutputThrowsReadable() {
    when(providerService.chat(any(), any(), any()))
        .thenReturn(new com.yokeos.core.provider.ProviderResponse("这不是 AGENT.md", null));
    org.mockito.Mockito.doThrow(
            new IllegalArgumentException("AGENT.md 缺少 frontmatter 围栏（首行必须是 ---）"))
        .when(agentLoader)
        .parse(any(), any(), any());

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> lifecycle.generate("一句话"));

    assertTrue(thrown.getMessage().contains("frontmatter"), "可读原因透传");
  }

  @Test
  @DisplayName("generate 配置缺失：IllegalStateException 且消息含配置键（→503，技 §3.3 不静默回退）")
  void generateWithoutConfigThrowsIllegalState() {
    AgentLifecycleService bare =
        new AgentLifecycleService(
            agentLoader,
            profileRegistry,
            agentScheduler,
            agentStore,
            providerService,
            "",
            "",
            Set.of("deepseek"));

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> bare.generate("一句话"));

    assertTrue(thrown.getMessage().contains("yokeos.agent-generation.provider"), "消息含配置方法");
    verify(providerService, never()).chat(any(), any(), any());
  }

  @Test
  @DisplayName("generate 空白句子：IllegalArgumentException（→400）")
  void generateBlankSentenceThrows() {
    assertThrows(IllegalArgumentException.class, () -> lifecycle.generate("  "));
    verify(providerService, never()).chat(any(), any(), any());
  }
}
