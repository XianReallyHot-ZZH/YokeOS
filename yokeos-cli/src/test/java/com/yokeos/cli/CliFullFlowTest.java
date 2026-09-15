package com.yokeos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.yokeos.channel.cli.CliChannel;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.SessionManager;
import com.yokeos.core.tool.ToolResult;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.storage.LlmCallRepository;
import com.yokeos.storage.ToolInvocationRepository;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 全流程 harness 双用例（第 18 节补强，兼作 debug 教学主线）：同一条链路——Bootstrap 加载 → AgentLoader 派生 Profile →
 * YokeosRuntime 装配 → CliChannel 交互 → AgentService/ReAct（思考→调工具→观察→续推）→ 双审计落库 → 会话保存 → 重进恢复 →
 * /context 与 /tools 展示。
 *
 * <p>离线版（默认跑）：模型 mock 预编排两轮——确定、无 key，IDEA 断点单步主线；echo 为零网络假工具。 真模型版（@Tag("integration") 显式触发）：真
 * DeepSeek + 真 http_get，全链无 mock，断言只锚链路产物（模型输出不可控）。 两者 @Primary 覆盖 providerMap（/tools）是 debug
 * 观察点，其余全链真实 Bean。
 */
class CliFullFlowTest {

  @TempDir static Path dbDir;

  @TempDir Path workspace;

  /** 预编排模型：静态 mock（@Primary Bean 需静态引用）。 */
  static final ChatModel CHAT_MODEL = Mockito.mock(ChatModel.class);

  /** 真 key（真模型用例；缺 key 时 assumeTrue 跳过）。 */
  private static final String DEEPSEEK_KEY = System.getenv("DEEPSEEK_API_KEY");

  @Test
  @DisplayName("全流程_一条走完_交互到引擎到落库到恢复")
  void fullWalkthrough_cliToEngineToPersistenceAndBack() throws Exception {
    writeWorkspaceWithEchoAgent();
    stubModelTwoRounds();
    String output = runChatSession();

    // 交互面断言：第二轮文本、/context 历史、/tools 审计记录
    assertTrue(output.contains("已收到 echo:世界"), "最终答复（含工具结果）打印: " + output);
    assertTrue(output.contains("会话上下文（4 条）"), "/context 展示完整历史");
    assertTrue(output.contains("user"), "历史带角色");
    assertTrue(output.contains("echo [成功]"), "/tools 展示审计记录");

    try (ConfigurableApplicationContext ctx = bootContext()) {
      // 审计断言：tool_invocations 与 llm_calls 真实落库
      ToolInvocationRepository toolRepo = ctx.getBean(ToolInvocationRepository.class);
      assertTrue(
          toolRepo.findBySessionId("cli:e2e-user:echo-agent").stream()
              .anyMatch(r -> Boolean.TRUE.equals(r.getSuccess()) && "echo".equals(r.getToolName())),
          "echo 成功调用落 tool_invocations");
      LlmCallRepository llmRepo = ctx.getBean(LlmCallRepository.class);
      assertTrue(llmRepo.count() >= 2, "两轮 LLM 调用各落一行 llm_calls");

      // 重进恢复断言：同一三元组拿到全部历史（跨进程续会话）
      SessionManager sessionManager = ctx.getBean(SessionManager.class);
      var restored = sessionManager.getOrCreate("cli", "e2e-user", "echo-agent");
      assertEquals(4, restored.messages().size(), "重进恢复全部历史（user/assistant/tool/assistant）");
      assertEquals("已收到 echo:世界", restored.messages().get(3).content());
      assertEquals("echo", restored.messages().get(2).toolName());

      // Profile 真实派生断言（AgentLoader 扫描 → 注册表可查）
      assertTrue(
          ctx.getBean(ProfileRegistry.class).get("echo-agent").isPresent(),
          "AGENT.md 派生 Profile 已注册");
    }
  }

  /**
   * 全流程·真模型（{@code @Tag("integration")}，显式触发 + 缺 key 跳过）：真 DeepSeek + 真 {@code http_get}（open-meteo
   * 无 key 端点，17 节冒烟同款强引导）——CLI 交互 → ReAct 思考调工具观察续推 → 双审计落库 → 会话保存 → 重进恢复，全链无 mock。
   * 模型输出不可控，断言只锚「链路产物」（工具调用留痕/审计行数/历史条数），不断言文本内容。
   *
   * <p>跑法：{@code DEEPSEEK_API_KEY=xxx mvn -pl yokeos-cli -am test -Dgroups=integration
   * -DexcludedGroups=}
   */
  @Test
  @org.junit.jupiter.api.Tag("integration")
  @DisplayName("全流程_真模型_真key真http_get从交互到落库到恢复")
  void fullWalkthroughWithRealModel_realKeyRealHttpTool() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        DEEPSEEK_KEY != null && !DEEPSEEK_KEY.isBlank(),
        "需要环境变量 DEEPSEEK_API_KEY 才能真调（缺 key 时跳过而非失败）");
    writeWorkspaceWithWeatherAgent();

    String sessionId = "cli:real-user:weather-agent";
    String output;
    try (ConfigurableApplicationContext ctx =
        bootContext(RealModelConfig.class, "full-flow-real.db")) {
      CliChannel channel = ctx.getBean(CliChannel.class);
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      channel.run(
          "weather-agent",
          "real-user",
          new BufferedReader(new StringReader("北京现在气温多少度？\n/tools\n/quit\n")),
          new PrintStream(buffer, true, StandardCharsets.UTF_8));
      output = buffer.toString(StandardCharsets.UTF_8);

      // 交互面：/tools 展示真实调用记录（强引导下模型必先调 http_get——17 节冒烟实证口径）
      assertTrue(output.contains("── Tool 调用记录"), "/tools 有输出段: " + output);
      assertTrue(output.contains("http_get"), "调用记录含 http_get: " + output);

      // 审计落库：工具成功留痕、LLM 调用留痕
      ToolInvocationRepository toolRepo = ctx.getBean(ToolInvocationRepository.class);
      assertTrue(
          toolRepo.findBySessionId(sessionId).stream()
              .anyMatch(
                  r -> Boolean.TRUE.equals(r.getSuccess()) && "http_get".equals(r.getToolName())),
          "http_get 成功调用落 tool_invocations");
      assertTrue(ctx.getBean(LlmCallRepository.class).count() >= 1, "LLM 调用落 llm_calls");

      // 重进恢复：同一三元组拿到全部历史（user / assistant / tool …）
      var restored =
          ctx.getBean(SessionManager.class).getOrCreate("cli", "real-user", "weather-agent");
      assertTrue(restored.messages().size() >= 2, "恢复历史至少含一问一答，实际 " + restored.messages().size());
      assertTrue(
          restored.messages().stream().anyMatch(m -> "http_get".equals(m.toolName())),
          "工具结果在恢复的历史中");
    }
  }

  /** 真模型工作区：weather-agent（provider deepseek、tools [http_get]、正文强引导先查 open-meteo 再作答）。 */
  private void writeWorkspaceWithWeatherAgent() throws Exception {
    Files.writeString(workspace.resolve("AGENTS.md"), "测试工作区约定");
    Files.writeString(workspace.resolve("SOUL.md"), "你是简洁实用的助手");
    Files.writeString(workspace.resolve("USER.md"), "无特殊偏好");
    Path agentDir = workspace.resolve("agents").resolve("weather-agent");
    Files.createDirectories(agentDir);
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\n"
            + "name: weather-agent\n"
            + "description: 真模型全流程测试 Agent\n"
            + "identity:\n"
            + "  agent_name: 天气小助\n"
            + "  prompt: 你是天气助手，回答天气问题必须先调用 http_get 工具获取实时数据再作答。\n"
            + "provider:\n"
            + "  name: deepseek\n"
            + "  model: deepseek-flash\n"
            + "tools:\n"
            + "  - http_get\n"
            + "---\n"
            + "回答天气问题时，必须先调用 http_get 访问 open-meteo 接口"
            + "（https://api.open-meteo.com/v1/forecast?latitude=39.9&longitude=116.4"
            + "&current=temperature_2m）获取实时天气，再基于结果作答。\n");
  }

  /** 模拟用户在终端敲四行：一句话 → /context → /tools → /quit（IO 注入，脚本化驱动）。 */
  private String runChatSession() {
    try (ConfigurableApplicationContext ctx = bootContext()) {
      CliChannel channel = ctx.getBean(CliChannel.class);
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      channel.run(
          "echo-agent",
          "e2e-user",
          new BufferedReader(new StringReader("帮我向世界打个招呼\n/context\n/tools\n/quit\n")),
          new PrintStream(buffer, true, StandardCharsets.UTF_8));
      return buffer.toString(StandardCharsets.UTF_8);
    }
  }

  /** 最小工作区：Bootstrap 三件 + echo-agent 的 AGENT.md（provider deepseek、tools [echo]）。 */
  private void writeWorkspaceWithEchoAgent() throws Exception {
    Files.writeString(workspace.resolve("AGENTS.md"), "测试工作区约定");
    Files.writeString(workspace.resolve("SOUL.md"), "你是简洁实用的助手");
    Files.writeString(workspace.resolve("USER.md"), "无特殊偏好");
    Path agentDir = workspace.resolve("agents").resolve("echo-agent");
    Files.createDirectories(agentDir);
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\n"
            + "name: echo-agent\n"
            + "description: 全流程测试 Agent\n"
            + "identity:\n"
            + "  agent_name: 回声助手\n"
            + "  prompt: 你是回声助手\n"
            + "provider:\n"
            + "  name: deepseek\n"
            + "  model: test-model\n"
            + "tools:\n"
            + "  - echo\n"
            + "---\n"
            + "收到请求先调 echo 工具再作答。\n");
  }

  /** 预编排两轮模型响应（写法照 SpringAiProviderServiceTest——17 节 harness）。 */
  private static void stubModelTwoRounds() {
    // 第一轮：提出调 echo
    AssistantMessage toolCallRound = mock(AssistantMessage.class);
    when(toolCallRound.getText()).thenReturn(null);
    when(toolCallRound.getToolCalls())
        .thenReturn(
            List.of(
                new AssistantMessage.ToolCall("call-1", "function", "echo", "{\"msg\":\"世界\"}")));
    ChatResponse first = mock(ChatResponse.class);
    when(first.getResult()).thenReturn(new Generation(toolCallRound));
    when(first.getMetadata()).thenReturn(mock(ChatResponseMetadata.class));

    // 第二轮：基于工具结果收尾
    ChatResponse second = mock(ChatResponse.class);
    when(second.getResult()).thenReturn(new Generation(new AssistantMessage("已收到 echo:世界")));
    when(second.getMetadata()).thenReturn(mock(ChatResponseMetadata.class));

    when(CHAT_MODEL.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(first, second);
  }

  /** 起装配面上下文：E2eConfig 用 @Primary 覆盖 providerMap（mock 模型）与 tools（echo 假工具），其余全链真实 Bean。 */
  private ConfigurableApplicationContext bootContext() {
    return bootContext(E2eConfig.class, "full-flow.db");
  }

  /** 上下文公共属性（临时 SQLite + schema 两脚本 + 临时工作区；库文件名分用例隔离）。 */
  private ConfigurableApplicationContext bootContext(Class<?> extraConfig, String dbName) {
    return new SpringApplicationBuilder(YokeosRuntime.class, extraConfig)
        .web(WebApplicationType.NONE)
        .properties(
            "spring.datasource.url=jdbc:sqlite:" + dbDir.resolve(dbName),
            "spring.datasource.driver-class-name=org.sqlite.JDBC",
            "spring.jpa.hibernate.ddl-auto=none",
            "spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect",
            "spring.sql.init.mode=always",
            "spring.sql.init.schema-locations=classpath:db/schema-001-audit.sql,"
                + "classpath:db/schema-002-sessions.sql",
            "yokeos.root=" + workspace.toAbsolutePath())
        .run();
  }

  /** debug 观察点覆盖：mock 模型 + echo 假工具（@Primary 优先于 YokeosRuntime 同类型 Bean）。 */
  @Configuration
  static class E2eConfig {

    @Bean
    @Primary
    Map<String, ChatModel> debugProviderMap() {
      return Map.of("deepseek", CHAT_MODEL);
    }

    @Bean
    @Primary
    Map<String, YokeTool> debugTools() {
      return Map.of("echo", new EchoTool());
    }
  }

  /** 真模型覆盖：只换 providerMap 为真 DeepSeek ChatModel（tools 用 YokeosRuntime 默认的真 http_get）。 */
  @Configuration
  static class RealModelConfig {

    @Bean
    @Primary
    Map<String, ChatModel> realProviderMap() {
      org.springframework.ai.openai.api.OpenAiApi api =
          org.springframework.ai.openai.api.OpenAiApi.builder()
              .baseUrl("https://api.deepseek.com")
              .apiKey(DEEPSEEK_KEY)
              .build();
      return Map.of(
          "deepseek",
          org.springframework.ai.openai.OpenAiChatModel.builder().openAiApi(api).build());
    }
  }

  /** 零网络假工具：回显入参 msg（真实工具的完整执行路径——解析、审计、回填全走）。 */
  static final class EchoTool implements YokeTool {

    @Override
    public String getName() {
      return "echo";
    }

    @Override
    public String getDescription() {
      return "回显一条消息";
    }

    @Override
    public String getInputSchema() {
      return "{\"type\":\"object\",\"properties\":{\"msg\":{\"type\":\"string\"}},"
          + "\"required\":[\"msg\"]}";
    }

    @Override
    public ToolResult execute(JsonNode input) {
      return ToolResult.ok("echo:" + input.path("msg").asText());
    }
  }
}
