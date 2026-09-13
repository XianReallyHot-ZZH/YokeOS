package com.yokeos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.Profile.Identity;
import com.yokeos.core.profile.Profile.ProviderConfig;
import com.yokeos.core.profile.Profile.Settings;
import com.yokeos.core.provider.ProviderRequest;
import com.yokeos.core.provider.ProviderResponse;
import com.yokeos.core.provider.ProviderService;
import com.yokeos.core.provider.ToolCallRequest;
import com.yokeos.core.session.Session;
import com.yokeos.core.tool.ToolResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * ReAct 循环调度 harness（docs/class/017-react-loop.md 第四部分）：判停、顺序执行、先累积再判停（坑三）、 轮数兜底恰好 N
 * 轮（坑一）、配置覆盖、空串收尾、失败回填不中断。契约上移红利：mock 的是自家接口。
 */
class ReActLoopTest {

  private final ProviderService providerService = mock(ProviderService.class);
  private final ToolExecutor toolExecutor = mock(ToolExecutor.class);
  private final PromptBuilder promptBuilder = mock(PromptBuilder.class);
  private final ReActLoop loop = new ReActLoop(promptBuilder, providerService, toolExecutor);

  private static final ToolCallRequest HTTP_GET =
      new ToolCallRequest("http_get", "{\"url\":\"https://api.open-meteo.com\"}");

  @Test
  @DisplayName("无工具调用_一轮收尾零工具执行")
  void noToolCallFinishesInOneRound() {
    when(promptBuilder.build(any(), any())).thenReturn(new ProviderRequest("p", List.of()));
    when(providerService.chat(any(), any(), any()))
        .thenReturn(new ProviderResponse("直接答复", List.of()));
    Session session = new Session("s-1", "ops-agent");

    String reply = loop.run(session, "你好", profileWith(Settings.DEFAULT));

    assertEquals("直接答复", reply);
    verify(providerService, times(1)).chat(any(), any(), any());
    verify(toolExecutor, never()).execute(any(), any());
  }

  @Test
  @DisplayName("有工具调用_执行回填后进下一轮")
  void withToolCallExecutesAndFeedsNextRound() {
    when(promptBuilder.build(any(), any())).thenReturn(new ProviderRequest("p", List.of()));
    when(providerService.chat(any(), any(), any()))
        .thenReturn(
            new ProviderResponse(null, List.of(HTTP_GET)),
            new ProviderResponse("基于工具数据的答复", List.of()));
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("20度晴"));
    Session session = new Session("s-1", "ops-agent");

    String reply = loop.run(session, "查天气", profileWith(Settings.DEFAULT));

    assertEquals("基于工具数据的答复", reply);
    verify(providerService, times(2)).chat(any(), any(), any());
    verify(toolExecutor, times(1)).execute("s-1", HTTP_GET);
    assertTrue(
        session.messages().stream()
            .anyMatch(m -> "tool".equals(m.role()) && "20度晴".equals(m.content())),
        "工具结果回填进 Session（下一轮 prompt 的输入源）");
  }

  @Test
  @DisplayName("一轮多个工具调用_逐个顺序执行")
  void multipleToolCallsExecutedSequentially() {
    ToolCallRequest second = new ToolCallRequest("http_get", "{\"url\":\"https://b\"}");
    when(promptBuilder.build(any(), any())).thenReturn(new ProviderRequest("p", List.of()));
    when(providerService.chat(any(), any(), any()))
        .thenReturn(new ProviderResponse(null, List.of(HTTP_GET, second)))
        .thenReturn(new ProviderResponse("done", List.of()));
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("r"));
    Session session = new Session("s-1", "ops-agent");

    loop.run(session, "两个站点", profileWith(Settings.DEFAULT));

    InOrder order = inOrder(toolExecutor);
    order.verify(toolExecutor).execute("s-1", HTTP_GET);
    order.verify(toolExecutor).execute("s-1", second); // 顺序执行不并行（技 §4.3 边界）
  }

  @Test
  @DisplayName("每轮响应与工具结果都累积进Session_转满轮数也全留痕")
  void everyRoundAccumulatesIntoSession() {
    when(promptBuilder.build(any(), any())).thenReturn(new ProviderRequest("p", List.of()));
    when(providerService.chat(any(), any(), any()))
        .thenReturn(new ProviderResponse(null, List.of(HTTP_GET))); // 永不收敛
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("data"));
    Session session = new Session("s-1", "ops-agent");

    loop.run(session, "死循环场景", profileWith(Settings.DEFAULT));

    long assistantCount =
        session.messages().stream().filter(m -> "assistant".equals(m.role())).count();
    long toolCount = session.messages().stream().filter(m -> "tool".equals(m.role())).count();
    assertEquals(10, assistantCount, "坑三回归：10 轮 assistant 消息一条不少（先累积再判停）");
    assertEquals(10, toolCount, "10 轮工具结果全留痕");
    assertEquals("user", session.messages().get(0).role(), "用户消息在最前");
  }

  @Test
  @DisplayName("模型一直要调工具_转满最大轮数强制停")
  void modelKeepsRequestingToolsForceStopAtMaxIterations() {
    when(promptBuilder.build(any(), any())).thenReturn(new ProviderRequest("p", List.of()));
    when(providerService.chat(any(), any(), any()))
        .thenReturn(new ProviderResponse(null, List.of(HTTP_GET)));
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("data"));
    Session session = new Session("s-1", "ops-agent");

    String reply = loop.run(session, "查天气", profileWith(Settings.DEFAULT));

    verify(providerService, times(10)).chat(any(), any(), any()); // 恰好 10 轮，一轮不多
    assertTrue(reply.contains("达到最大轮数"), "强制收尾答复可辨认（测试断言字面量）");
  }

  @Test
  @DisplayName("最大轮数按Agent配置生效_5轮即停")
  void maxIterationsOverrideFromProfileFiveRounds() {
    when(promptBuilder.build(any(), any())).thenReturn(new ProviderRequest("p", List.of()));
    when(providerService.chat(any(), any(), any()))
        .thenReturn(new ProviderResponse(null, List.of(HTTP_GET)));
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.ok("data"));
    Session session = new Session("s-1", "ops-agent");

    String reply = loop.run(session, "查天气", profileWith(new Settings(5, 20)));

    verify(providerService, times(5)).chat(any(), any(), any());
    assertTrue(reply.contains("达到最大轮数"));
  }

  @Test
  @DisplayName("响应既无文本也无工具调用_按空串收尾")
  void neitherTextNorToolCallsReturnsEmptyString() {
    when(promptBuilder.build(any(), any())).thenReturn(new ProviderRequest("p", List.of()));
    when(providerService.chat(any(), any(), any()))
        .thenReturn(new ProviderResponse(null, List.of()));
    Session session = new Session("s-1", "ops-agent");

    String reply = loop.run(session, "边界", profileWith(Settings.DEFAULT));

    assertEquals("", reply);
  }

  @Test
  @DisplayName("工具执行失败_结果回填循环继续不中断")
  void toolFailureResultFedBackLoopContinues() {
    when(promptBuilder.build(any(), any())).thenReturn(new ProviderRequest("p", List.of()));
    when(providerService.chat(any(), any(), any()))
        .thenReturn(
            new ProviderResponse(null, List.of(HTTP_GET)),
            new ProviderResponse("失败后继续的答复", List.of()));
    when(toolExecutor.execute(any(), any())).thenReturn(ToolResult.error("connect refused", false));
    Session session = new Session("s-1", "ops-agent");

    String reply = loop.run(session, "查天气", profileWith(Settings.DEFAULT));

    assertEquals("失败后继续的答复", reply, "失败结果交还循环——模型下一轮看到原因自行决定");
    assertTrue(
        session.messages().stream()
            .anyMatch(m -> "tool".equals(m.role()) && m.content().contains("connect refused")),
        "失败原因回填进历史");
  }

  private static Profile profileWith(Settings settings) {
    return new Profile(
        "ops-agent",
        "运维助手",
        new Identity("运维小欧", "你是运维助手"),
        new ProviderConfig("deepseek", "test-model", 0.7),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        settings);
  }
}
