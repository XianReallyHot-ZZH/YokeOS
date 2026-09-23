package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yokeos.core.agent.AgentService;
import com.yokeos.core.session.SessionManager;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.storage.LlmCallRepository;
import com.yokeos.storage.ToolInvocationRepository;
import com.yokeos.tool.NotifyTools;
import com.yokeos.tool.ToolRegistry;
import com.yokeos.tool.builtin.HttpTools;
import com.yokeos.tool.notify.WebhookNotifyAdapter;
import com.yokeos.tool.sandbox.SandboxProperties;
import com.yokeos.tool.sandbox.WhitelistSandbox;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 人推链路真 key 三支柱（第 27 节，{@code @Tag("integration")} 显式触发 + 缺 key 类级跳过）：真 DeepSeek + 真
 * http_get（open-meteo 无 key 端点）+ 真 notify（本地 HttpServer 扮群机器人，19 节替身同款），一次「天气穿搭推送」
 * 对话逐表对账<b>不多不少</b>（拍板①：对账场景纳入 Notify，31 节 Demo 一人推补跑形态预演）。
 *
 * <p>与分节 E2E 的分工：17/19/22 节各验一段能力，本类把<b>一次对话穿全部八站</b>的账一次对齐——sessions / llm_calls /
 * tool_invocations 按 sessionId 过滤恰量断言（坑四），另覆盖记忆支柱、工具清单支柱、失败路径落账（坑六）、cli/web 两入口同库
 * （三面同源的自动化可证部分；管理台读同一组端点，26 节人工项已目检）。
 *
 * <p>hermetic：{@code yokeos.root} 指临时工作区（沙箱 file 白名单自动跟，坑七）、db 指临时目录、域名白名单经 {@code @Primary} tools
 * 自备（24 节坑：真上下文测试不撞 deny-all；白名单 = open-meteo + 本地接收端）。
 *
 * <p>跑法：
 *
 * <pre>
 * source ~/.zshrc && mvn -pl yokeos-boot -am test \
 *     -Dgroups=integration -DexcludedGroups= -Dtest='HumanTriggerFlowIntegrationTest'
 * </pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HumanTriggerFlowIntegrationTest {

  private static final String AGENT = "weather-push-agent";

  @TempDir static Path workspace;

  static HttpServer webhookReceiver;

  static final List<String> receivedBodies = new ArrayList<>();

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired TestRestTemplate rest;

  @Autowired AgentService agentService;

  @Autowired SessionManager sessionManager;

  @Autowired LlmCallRepository llmCallRepository;

  @Autowired ToolInvocationRepository toolInvocationRepository;

  /**
   * 真上下文 tools 覆盖（24 节坑先例）：域名白名单自备 open-meteo + 本地接收端。四件全注册——Profile 点名的工具必须在候选集（19
   * 节坑：点名不在集的静默略过，模型零工具口头答复，支柱二必红）。
   */
  @TestConfiguration
  static class ToolWhitelistConfig {

    @Bean
    @Primary
    Map<String, YokeTool> itTools() {
      WhitelistSandbox sandbox =
          new WhitelistSandbox(
              new SandboxProperties(
                  List.of(workspace.toAbsolutePath().toString()),
                  List.of(),
                  List.of("localhost", "127.0.0.1", "api.open-meteo.com")));
      // 记忆走独立实例但同一路径文件（workspace/memory），与上下文 memoryService Bean 读写同一 MEMORY.md
      var memoryTools =
          new com.yokeos.memory.builtin.MemoryTools(
              new com.yokeos.memory.MemoryServiceImpl(
                  new com.yokeos.memory.MarkdownMemoryStore(
                      workspace.resolve("memory"), 4000, sandbox)));
      ToolRegistry registry = new ToolRegistry();
      registry.registerAnnotated(new HttpTools(sandbox));
      registry.registerAnnotated(memoryTools);
      registry.register(new NotifyTools(Map.of("webhook", new WebhookNotifyAdapter()), sandbox));
      return registry.asMap();
    }
  }

  @DynamicPropertySource
  static void pinWorkspace(DynamicPropertyRegistry registry) {
    registry.add("yokeos.root", () -> workspace.toAbsolutePath().toString());
    registry.add("yokeos.db.dir", () -> workspace.resolve("db").toAbsolutePath().toString());
  }

  @BeforeAll
  static void startReceiverAndWriteWorkspace() throws IOException {
    Files.createDirectories(workspace.resolve("db")); // SQLite 不自动建父目录
    Files.writeString(workspace.resolve("AGENTS.md"), "测试工作区约定");
    Files.writeString(workspace.resolve("SOUL.md"), "你是简洁实用的助手");
    Files.writeString(workspace.resolve("USER.md"), "无特殊偏好");
    webhookReceiver = HttpServer.create(new InetSocketAddress(0), 0);
    webhookReceiver.createContext(
        "/hook",
        exchange -> {
          receivedBodies.add(new String(exchange.getRequestBody().readAllBytes()));
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    webhookReceiver.start();
    // notify 渠道明文 URL（非凭证不强制占位；占位解析链 19 节已守，这里锚对账不锚占位）
    String receiverUrl = "http://127.0.0.1:" + webhookReceiver.getAddress().getPort() + "/hook";
    Path agentDir = workspace.resolve("agents").resolve(AGENT);
    Files.createDirectories(agentDir);
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\n"
            + "name: "
            + AGENT
            + "\n"
            + "identity:\n"
            + "  agent_name: 天气穿搭助手\n"
            + "  prompt: 你是天气助手。回答天气问题必须先调用 http_get 获取实时数据再作答；"
            + "生成穿搭建议后必须调用 notify 工具把建议推送一次（channel 填 team-im，只推一次）；"
            + "用户要求记住信息时必须调用 save_memory（scope 填 archival）。\n"
            + "provider:\n"
            + "  name: deepseek\n"
            + "  model: deepseek-flash\n"
            + "tools:\n"
            + "  - http_get\n"
            + "  - notify\n"
            + "  - save_memory\n"
            + "  - recall_memory\n"
            + "notify:\n"
            + "  channels:\n"
            + "    - name: team-im\n"
            + "      type: webhook\n"
            + "      config:\n"
            + "        url: "
            + receiverUrl
            + "\n"
            + "---\n"
            + "查天气用 open-meteo：https://api.open-meteo.com/v1/forecast?latitude=39.9&"
            + "longitude=116.4&current=temperature_2m。\n");
  }

  @AfterAll
  static void stopReceiver() {
    if (webhookReceiver != null) {
      webhookReceiver.stop(0);
    }
  }

  @Test
  @DisplayName("支柱一_天气穿搭推送一次对话_逐表对账不多不少")
  void pillarOne_conversationAndAuditReconciled() throws Exception {
    String sessionId =
        dataOf(post("/api/v1/sessions", "{\"profile\":\"" + AGENT + "\"}"))
            .get("sessionId")
            .asText();
    assertEquals("web:default:" + AGENT, sessionId, "web 渠道三元组口径");

    JsonNode reply =
        dataOf(
            post(
                "/api/v1/sessions/" + sessionId + "/messages",
                "{\"content\":\"查一下北京现在的天气，给出穿搭建议，并把建议推送到群里。\"}"));
    assertFalse(reply.get("reply").asText().isBlank(), "最终答复应非空");

    // 历史：user + assistant（含 tool call）+ tool 结果 + assistant ≥ 4 条
    JsonNode history = dataOf(get("/api/v1/sessions/" + sessionId));
    assertTrue(
        history.get("messages").size() >= 4,
        "历史应含完整往来（实际: " + history.get("messages").size() + "）");

    // 逐表对账（坑四：按 sessionId 过滤）——http_get ≥1 全成功、notify 恰 1 成功、llm_calls ≥2
    var toolRows = toolInvocationRepository.findBySessionId(sessionId);
    long httpGetCount = toolRows.stream().filter(r -> "http_get".equals(r.getToolName())).count();
    long notifyCount = toolRows.stream().filter(r -> "notify".equals(r.getToolName())).count();
    assertTrue(httpGetCount >= 1, "http_get 至少一次（实际: " + dumpTools(sessionId) + "）");
    assertEquals(1, notifyCount, "notify 恰一次——推送不多不少（实际: " + dumpTools(sessionId) + "）");
    assertTrue(
        toolRows.stream().allMatch(r -> Boolean.TRUE.equals(r.getSuccess())),
        "两次涉外调用都必须成功：" + dumpTools(sessionId));
    long llmCount =
        llmCallRepository.findAll().stream()
            .filter(l -> sessionId.equals(l.getSessionId()))
            .count();
    assertTrue(llmCount >= 2, "至少两轮 LLM 调用（实际: " + llmCount + "）");

    // 出站物理证据：群机器人替身真收到 POST
    synchronized (receivedBodies) {
      assertFalse(receivedBodies.isEmpty(), "接收端必须收到推送（body: " + receivedBodies + "）");
    }

    // 列表可见（三面同源 · REST 面）
    JsonNode list = dataOf(get("/api/v1/sessions"));
    assertTrue(
        StreamSupport.stream(list.spliterator(), false)
            .anyMatch(n -> sessionId.equals(n.get("sessionId").asText())),
        "会话应出现在 GET /sessions 列表");
  }

  @Test
  @DisplayName("支柱二_记忆写得进查得到_真模型save_memory")
  void pillarTwo_memoryWrittenAndReadable() throws Exception {
    String sessionId =
        dataOf(post("/api/v1/sessions", "{\"profile\":\"" + AGENT + "\"}"))
            .get("sessionId")
            .asText();
    String marker = "27节IT记忆-3b9f";
    post(
        "/api/v1/sessions/" + sessionId + "/messages",
        "{\"content\":\"请记住：" + marker + "，我在北京，怕冷。\"}");

    String memory = dataOf(get("/api/v1/memory")).toString();
    assertTrue(memory.contains("北京"), "GET /memory 应查得到刚写入的事实（实际: " + memory + "）");
  }

  @Test
  @DisplayName("支柱三_工具清单对外可见")
  void pillarThree_toolsVisible() throws Exception {
    JsonNode tools = dataOf(get("/api/v1/tools"));
    assertTrue(tools.size() > 0, "工具清单数量应大于 0");
    List<String> names =
        StreamSupport.stream(tools.spliterator(), false).map(n -> n.get("name").asText()).toList();
    assertTrue(
        names.containsAll(List.of("http_get", "notify", "save_memory")),
        "清单应含对账三件（实际: " + names + "）");
  }

  @Test
  @DisplayName("三面同源_cli与web两入口同引擎同库_列表两态并见")
  void sameEngine_twoChannelsSameStore() throws Exception {
    // CLI 面：同一 AgentService 引擎，三元组 channel=cli（18 节判）
    var cliSession = sessionManager.getOrCreate("cli", "it-user", AGENT);
    agentService.process(cliSession, "只回答：收到。不要调用任何工具。");

    JsonNode list = dataOf(get("/api/v1/sessions"));
    List<String> ids =
        StreamSupport.stream(list.spliterator(), false)
            .map(n -> n.get("sessionId").asText())
            .toList();
    assertTrue(ids.contains("cli:it-user:" + AGENT), "列表应见 cli 会话（实际: " + ids + "）");
    assertTrue(
        ids.stream().anyMatch(id -> id.startsWith("web:")), "列表应同时见 web 会话（实际: " + ids + "）");
  }

  @Test
  @DisplayName("失败路径_错key调用_llm_calls落success_false且系统不崩")
  void failurePath_wrongKeyStillAudited() {
    // 坑六：失败那一半的账——错 key 401 → NonTransientAiException（26 节 503 映射实证）→ 本节锚落账
    // 手工小段：错 key provider 直调 SpringAiProviderService（真库 auditor），系统主体不受影响
    var api =
        org.springframework.ai.openai.api.OpenAiApi.builder()
            .baseUrl("https://api.deepseek.com")
            .apiKey("sk-deliberately-wrong-27")
            .build();
    var wrongKeyModel =
        org.springframework.ai.openai.OpenAiChatModel.builder().openAiApi(api).build();
    var provider =
        new com.yokeos.provider.SpringAiProviderService(
            Map.of("deepseek", wrongKeyModel),
            new com.yokeos.provider.ToolSchemaAdapter(),
            new com.yokeos.storage.JpaLlmCallAuditor(llmCallRepository));
    var profile =
        new com.yokeos.core.profile.Profile(
            "fail-probe",
            "失败探针",
            new com.yokeos.core.profile.Profile.Identity("探针", "ping"),
            new com.yokeos.core.profile.Profile.ProviderConfig("deepseek", "deepseek-flash", null),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            com.yokeos.core.profile.Profile.Settings.DEFAULT);
    try {
      provider.chat(
          "it-fail-27",
          profile,
          new com.yokeos.core.provider.ProviderRequest(
              "ping",
              List.of(new com.yokeos.core.session.Message("user", "ping", null)),
              List.of()));
      throw new AssertionError("错 key 必须抛异常");
    } catch (RuntimeException expected) {
      // 401 家族异常（类型不锚死，26 节已钉 503 映射；本用例锚审计）
    }
    assertTrue(
        llmCallRepository.findAll().stream()
            .anyMatch(
                l -> "it-fail-27".equals(l.getSessionId()) && !Boolean.TRUE.equals(l.getSuccess())),
        "失败调用必须落 llm_calls success=false");
  }

  // —— helpers ——

  private ResponseEntity<String> post(String path, String json) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    return rest.postForEntity(path, new HttpEntity<>(json, headers), String.class);
  }

  private ResponseEntity<String> get(String path) {
    return rest.getForEntity(path, String.class);
  }

  private JsonNode dataOf(ResponseEntity<String> response) throws Exception {
    assertEquals(
        200, response.getStatusCode().value(), "HTTP 应 200（body: " + response.getBody() + "）");
    JsonNode body = mapper.readTree(response.getBody());
    assertEquals(0, body.get("code").asInt(), "统一信封 code 应为 0（body: " + response.getBody() + "）");
    return body.get("data");
  }

  private String dumpTools(String sessionId) {
    return toolInvocationRepository.findBySessionId(sessionId).stream()
        .map(r -> r.getToolName() + "(" + r.getSuccess() + ")")
        .toList()
        .toString();
  }
}
