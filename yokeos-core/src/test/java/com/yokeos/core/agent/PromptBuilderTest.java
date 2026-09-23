package com.yokeos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.yokeos.core.context.ContextLoader;
import com.yokeos.core.memory.MemoryService;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.Profile.Identity;
import com.yokeos.core.profile.Profile.ProviderConfig;
import com.yokeos.core.profile.Profile.Settings;
import com.yokeos.core.provider.ProviderRequest;
import com.yokeos.core.provider.ProviderResponse;
import com.yokeos.core.provider.ToolCallRequest;
import com.yokeos.core.session.Message;
import com.yokeos.core.session.Session;
import com.yokeos.core.tool.ToolResult;
import com.yokeos.core.tool.YokeTool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * PromptBuilder 组装 harness（docs/class/017-react-loop.md 第四部分 + 022 记忆位 + 031 结构化改造）： 系统段固定顺序（system
 * prompt + 日期时间行 + 长期记忆位）、轮界截断（坑二）、availableTools 只带点名工具； 31 节起历史以结构化清单传递（不再拉平进文本）—— assistant 的
 * toolCalls 与 tool 的 toolCallId 原样透传（复读方根因修复的回归守点）。截断用例经 Profile Settings 把 maxHistoryTurns 压到 2。
 */
class PromptBuilderTest {

  /** 固定时钟：Asia/Shanghai 下 2026-09-13 10:00:00——期望值按它计算。 */
  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.parse("2026-09-13T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  private static final String EXPECTED_DATETIME = "当前时间：2026-09-13 10:00:00";

  /** 截断用例的 profile：maxHistoryTurns=2。 */
  private static final Settings TWO_TURNS = new Settings(10, 2);

  /** 最近一次 builderWith 造的 mock 门面（verify 用）。 */
  private MemoryService memory;

  @TempDir Path workspace;

  @Test
  @DisplayName("系统段顺序_system在前日期时间行居中记忆在后_历史独立成段")
  void sectionsInOrderSystemWithDatetimeThenHistory() throws IOException {
    PromptBuilder builder = builderWith(Map.of());
    Session session = new Session("s-1", "ops-agent");
    session.appendUser("第一句");

    ProviderRequest request = builder.build(session, profileTools(List.of(), Settings.DEFAULT));

    String text = request.systemPrompt();
    int system = text.indexOf("你是运维小欧的人格底座");
    int datetime = text.indexOf(EXPECTED_DATETIME);
    assertTrue(system >= 0, "system prompt 在场");
    assertTrue(datetime > system, "日期时间行在 system prompt 末尾（模型自己不知道今天几号）");
    assertFalse(text.contains("第一句"), "31 节结构化改造：历史不进系统段文本");
    assertEquals(1, request.history().size(), "历史以结构化清单独立传递");
    assertEquals("user", request.history().get(0).role());
    assertEquals("第一句", request.history().get(0).content());
  }

  @Test
  @DisplayName("历史超过轮数上限_以轮为界截断")
  void historyOverLimitTruncatedByTurn() throws IOException {
    PromptBuilder builder = builderWith(Map.of());
    Session session = new Session("s-1", "ops-agent");
    for (int i = 1; i <= 3; i++) {
      session.appendUser("第" + i + "轮提问");
      session.appendAssistant(new ProviderResponse("第" + i + "轮回答", List.of()));
    }

    ProviderRequest request = builder.build(session, profileTools(List.of(), TWO_TURNS));

    assertFalse(
        request.history().stream().anyMatch(m -> m.content().contains("第1轮提问")),
        "坑二回归：超 maxHistoryTurns=2 的整轮被截");
    assertFalse(
        request.history().stream().anyMatch(m -> m.content().contains("第1轮回答")), "轮内消息同进同出");
    assertTrue(
        request.history().stream().anyMatch(m -> m.content().contains("第2轮提问")),
        "最近 2 轮保留（第 2 轮在场）");
    assertTrue(
        request.history().stream().anyMatch(m -> m.content().contains("第3轮提问")),
        "最近 2 轮保留（第 3 轮在场）");
  }

  @Test
  @DisplayName("历史恰好等于上限_不截断")
  void historyExactlyLimitNotTruncated() throws IOException {
    PromptBuilder builder = builderWith(Map.of());
    Session session = new Session("s-1", "ops-agent");
    session.appendUser("第1轮提问");
    session.appendAssistant(new ProviderResponse("答1", List.of()));
    session.appendUser("第2轮提问");
    session.appendAssistant(new ProviderResponse("答2", List.of()));

    ProviderRequest request = builder.build(session, profileTools(List.of(), TWO_TURNS));

    assertEquals(4, request.history().size(), "恰好 2 轮（4 条消息）全保留");
  }

  @Test
  @DisplayName("system末尾日期时间行_由注入Clock计算")
  void systemPromptEndsWithCurrentDatetime() throws IOException {
    PromptBuilder builder = builderWith(Map.of());
    Session session = new Session("s-1", "ops-agent");
    session.appendUser("问");

    ProviderRequest request = builder.build(session, profileTools(List.of(), Settings.DEFAULT));

    assertTrue(request.systemPrompt().contains(EXPECTED_DATETIME), "期望值由固定时钟算出，不解析实现格式反推");
  }

  @Test
  @DisplayName("可用工具只带Profile点名的")
  void availableToolsOnlyProfileNamed() throws IOException {
    YokeTool named = stubTool("http_get");
    YokeTool other = stubTool("shell");
    PromptBuilder builder = builderWith(Map.of("http_get", named, "shell", other));
    Session session = new Session("s-1", "ops-agent");

    ProviderRequest request =
        builder.build(session, profileTools(List.of("http_get"), Settings.DEFAULT));

    assertEquals(1, request.availableTools().size());
    assertEquals("http_get", request.availableTools().get(0).getName(), "只带 Profile 点名的工具");
  }

  @Test
  @DisplayName("轮界截断不撕裂_工具结果跟住它的提问轮")
  void truncationKeepsToolResultWithItsTurn() throws IOException {
    PromptBuilder builder = builderWith(Map.of());
    Session session = new Session("s-1", "ops-agent");
    // 第 1 轮（将被截掉）：user + assistant(toolCall) + tool 结果
    session.appendUser("第1轮提问");
    session.appendAssistant(
        new ProviderResponse(null, List.of(new ToolCallRequest("call-9", "http_get", "{}"))));
    session.appendToolResult(
        new ToolCallRequest("call-9", "http_get", "{}"), ToolResult.ok("第1轮工具结果"));
    // 第 2、3 轮（保留）
    session.appendUser("第2轮提问");
    session.appendAssistant(new ProviderResponse("答2", List.of()));
    session.appendUser("第3轮提问");
    session.appendAssistant(new ProviderResponse("答3", List.of()));

    ProviderRequest request = builder.build(session, profileTools(List.of(), TWO_TURNS));

    assertFalse(
        request.history().stream().anyMatch(m -> m.content().contains("第1轮工具结果")),
        "整轮截断——工具结果不孤悬（不撕裂）");
    assertTrue(
        request.history().stream().anyMatch(m -> m.content().contains("第2轮提问")), "边界正确：最近 2 轮完整保留");
  }

  @Test
  @DisplayName("结构化历史透传_assistant带toolCalls_tool带配对id")
  void structuredHistoryPassesToolCallPairing() throws IOException {
    PromptBuilder builder = builderWith(Map.of());
    Session session = new Session("s-1", "ops-agent");
    ToolCallRequest call = new ToolCallRequest("call-7", "http_get", "{\"url\":\"https://a\"}");
    session.appendUser("取数");
    session.appendAssistant(new ProviderResponse(null, List.of(call)));
    session.appendToolResult(call, ToolResult.ok("结果体"));

    ProviderRequest request = builder.build(session, profileTools(List.of(), Settings.DEFAULT));

    Message assistant =
        request.history().stream()
            .filter(m -> "assistant".equals(m.role()))
            .findFirst()
            .orElseThrow();
    assertEquals(1, assistant.toolCalls().size(), "assistant 消息携带 toolCalls（31 节结构化回传）");
    assertEquals("call-7", assistant.toolCalls().get(0).id(), "协议配对 id 原样透传");
    Message tool =
        request.history().stream().filter(m -> "tool".equals(m.role())).findFirst().orElseThrow();
    assertEquals("call-7", tool.toolCallId(), "tool 结果带 toolCallId 与 assistant.toolCalls 配对");
    assertEquals("http_get", tool.toolName());
  }

  @Test
  @DisplayName("长期记忆注入在系统段_历史独立不混入")
  void memoryInjectedBetweenSystemAndHistory() throws IOException {
    PromptBuilder builder = builderWith(Map.of(), "## 核心记忆\n- [2026-09-18] 用户偏好中文交流");
    Session session = new Session("s-1", "ops-agent");
    session.appendUser("今天天气如何");

    ProviderRequest request = builder.build(session, profileTools(List.of(), Settings.DEFAULT));

    String text = request.systemPrompt();
    int system = text.indexOf("你是运维小欧的人格底座");
    assertTrue(system >= 0, "system prompt 在场");
    int datetime = text.indexOf(EXPECTED_DATETIME);
    assertTrue(datetime > system, "日期时间行在 system prompt 之后");
    int memory = text.indexOf("用户偏好中文交流");
    assertTrue(memory > datetime, "长期记忆在日期时间行之后（技 §4.2 [2] 位，17 节恒空位兑现）");
    assertEquals("今天天气如何", request.history().get(0).content(), "对话历史独立成段（结构化清单首位）");
  }

  @Test
  @DisplayName("会话历史只由历史段承载_不重复注入")
  void historyNotDuplicatedWhenMemoryInjected() throws IOException {
    PromptBuilder builder = builderWith(Map.of(), "## 核心记忆\n- [2026-09-18] 一条核心记忆");
    Session session = new Session("s-1", "ops-agent");
    session.appendUser("只出现一次的提问");

    ProviderRequest request = builder.build(session, profileTools(List.of(), Settings.DEFAULT));

    long occurrences =
        request.history().stream().filter(m -> "只出现一次的提问".equals(m.content())).count();
    assertEquals(1, occurrences, "坑七回归：历史段恰好一条（buildContext 只出长期记忆）");
    assertFalse(request.systemPrompt().contains("只出现一次的提问"), "历史不混入系统段");
  }

  @Test
  @DisplayName("每次组装都现读长期记忆")
  void memoryReadFreshOnEveryBuild() throws IOException {
    PromptBuilder builder = builderWith(Map.of(), "记忆内容");
    Session session = new Session("s-1", "ops-agent");
    session.appendUser("问1");
    Session second = new Session("s-2", "ops-agent");
    second.appendUser("问2");

    builder.build(session, profileTools(List.of(), Settings.DEFAULT));
    builder.build(second, profileTools(List.of(), Settings.DEFAULT));

    Mockito.verify(memory, Mockito.times(2)).buildContext(Mockito.any());
  }

  private PromptBuilder builderWith(Map<String, YokeTool> tools) throws IOException {
    return builderWith(tools, "");
  }

  /** 22 节构造器扩展：memory 槽内容由 mock 门面供给（缺省空串 = 既有用例的恒空记忆位）。 */
  private PromptBuilder builderWith(Map<String, YokeTool> tools, String memoryContext)
      throws IOException {
    writeMinimalWorkspace();
    memory = Mockito.mock(MemoryService.class);
    Mockito.when(memory.buildContext(Mockito.any())).thenReturn(memoryContext);
    return new PromptBuilder(new ContextLoader(workspace), tools, FIXED_CLOCK, memory);
  }

  /** 最小工作区：Bootstrap 三件 + 一个 AGENT.md。 */
  private void writeMinimalWorkspace() throws IOException {
    Files.writeString(workspace.resolve("AGENTS.md"), "约定");
    Files.writeString(workspace.resolve("SOUL.md"), "人格");
    Files.writeString(workspace.resolve("USER.md"), "偏好");
    Path agentDir = workspace.resolve("agents").resolve("ops-agent");
    Files.createDirectories(agentDir);
    Files.writeString(agentDir.resolve("AGENT.md"), "---\nname: ops-agent\n---\n你是运维小欧的人格底座");
  }

  private static Profile profileTools(List<String> tools, Settings settings) {
    return new Profile(
        "ops-agent",
        "运维助手",
        new Identity("运维小欧", "你是运维小欧的人格底座"),
        new ProviderConfig("deepseek", "test-model", 0.7),
        tools,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        settings);
  }

  private static YokeTool stubTool(String name) {
    return new YokeTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return "测试桩";
      }

      @Override
      public String getInputSchema() {
        return "{\"type\":\"object\"}";
      }

      @Override
      public ToolResult execute(JsonNode input) {
        return ToolResult.ok("stub");
      }
    };
  }
}
