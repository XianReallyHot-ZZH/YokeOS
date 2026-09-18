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
 * PromptBuilder 组装 harness（docs/class/017-react-loop.md 第四部分 + 022 第 22 节接线）：固定顺序、日期时间行（Clock 注入
 * 断言——不赌真实时间）、轮界截断（坑二：超 N 轮截、恰好 N 轮不截、工具结果跟住提问轮）、availableTools 只带 Profile 点名工具； 22 节新增——[2]
 * 长期记忆位接线（注入位置、历史不重复、每次组装现调）。截断用例经 Profile Settings 把 maxHistoryTurns 压到 2。
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
  @DisplayName("拼接顺序_system带日期时间行在前历史在后")
  void sectionsInOrderSystemWithDatetimeThenHistory() throws IOException {
    PromptBuilder builder = builderWith(Map.of());
    Session session = new Session("s-1", "ops-agent");
    session.appendUser("第一句");

    String text = builder.build(session, profileTools(List.of(), Settings.DEFAULT)).promptText();

    int system = text.indexOf("你是运维小欧的人格底座");
    int datetime = text.indexOf(EXPECTED_DATETIME);
    int history = text.indexOf("第一句");
    assertTrue(system >= 0, "system prompt 在场");
    assertTrue(datetime > system, "日期时间行在 system prompt 末尾（模型自己不知道今天几号）");
    assertTrue(history > datetime, "对话历史在记忆位（恒空）之后");
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

    String text = builder.build(session, profileTools(List.of(), TWO_TURNS)).promptText();

    assertFalse(text.contains("第1轮提问"), "坑二回归：超 maxHistoryTurns=2 的整轮被截");
    assertFalse(text.contains("第1轮回答"), "轮内消息同进同出");
    assertTrue(text.contains("第2轮提问") && text.contains("第3轮提问"), "最近 2 轮保留");
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

    String text = builder.build(session, profileTools(List.of(), TWO_TURNS)).promptText();

    assertTrue(text.contains("第1轮提问") && text.contains("第2轮提问"), "恰好 2 轮全保留");
  }

  @Test
  @DisplayName("system末尾日期时间行_由注入Clock计算")
  void systemPromptEndsWithCurrentDatetime() throws IOException {
    PromptBuilder builder = builderWith(Map.of());
    Session session = new Session("s-1", "ops-agent");
    session.appendUser("问");

    String text = builder.build(session, profileTools(List.of(), Settings.DEFAULT)).promptText();

    assertTrue(text.contains(EXPECTED_DATETIME), "期望值由固定时钟算出，不解析实现格式反推");
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
        new ProviderResponse(null, List.of(new ToolCallRequest("http_get", "{}"))));
    session.appendToolResult("http_get", ToolResult.ok("第1轮工具结果"));
    // 第 2、3 轮（保留）
    session.appendUser("第2轮提问");
    session.appendAssistant(new ProviderResponse("答2", List.of()));
    session.appendUser("第3轮提问");
    session.appendAssistant(new ProviderResponse("答3", List.of()));

    String text = builder.build(session, profileTools(List.of(), TWO_TURNS)).promptText();

    assertFalse(text.contains("第1轮工具结果"), "整轮截断——工具结果不孤悬（不撕裂）");
    assertTrue(text.contains("第2轮提问"), "边界正确：最近 2 轮完整保留");
  }

  @Test
  @DisplayName("长期记忆注入在系统提示与对话历史之间")
  void memoryInjectedBetweenSystemAndHistory() throws IOException {
    PromptBuilder builder = builderWith(Map.of(), "## 核心记忆\n- [2026-09-18] 用户偏好中文交流");
    Session session = new Session("s-1", "ops-agent");
    session.appendUser("今天天气如何");

    String text = builder.build(session, profileTools(List.of(), Settings.DEFAULT)).promptText();

    int system = text.indexOf("你是运维小欧的人格底座");
    assertTrue(system >= 0, "system prompt 在场");
    int datetime = text.indexOf(EXPECTED_DATETIME);
    assertTrue(datetime > system, "日期时间行在 system prompt 之后");
    int memory = text.indexOf("用户偏好中文交流");
    assertTrue(memory > datetime, "长期记忆在日期时间行之后（技 §4.2 [2] 位，17 节恒空位兑现）");
    int history = text.indexOf("今天天气如何");
    assertTrue(history > memory, "对话历史在记忆位之后——system → 时间行 → 记忆 → 历史的固定顺序");
  }

  @Test
  @DisplayName("会话历史只由历史段承载_不重复注入")
  void historyNotDuplicatedWhenMemoryInjected() throws IOException {
    PromptBuilder builder = builderWith(Map.of(), "## 核心记忆\n- [2026-09-18] 一条核心记忆");
    Session session = new Session("s-1", "ops-agent");
    session.appendUser("只出现一次的提问");

    String text = builder.build(session, profileTools(List.of(), Settings.DEFAULT)).promptText();

    int occurrences = text.split("只出现一次的提问", -1).length - 1;
    assertEquals(1, occurrences, "坑七回归：记忆注入后历史恰好出现一次（buildContext 只出长期记忆）");
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
