package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.yokeos.core.agent.AgentScheduler;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.storage.ScheduledTask;
import com.yokeos.storage.ScheduledTaskRepository;
import com.yokeos.storage.TaskExecution;
import com.yokeos.storage.TaskExecutionRepository;
import com.yokeos.storage.ToolInvocationRepository;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 钟推链路真 key 全链对账（第 28 节，{@code @Tag("integration")} + 缺 key 类级跳过）：技 §12.1 Demo 一「每日天气」的钟推 形态预演——
 * {@code runNow}（人推补跑入口，与 cron 到点同一条 execute 路径）确定性驱动，<b>两次触发验证会话复用</b>（坑一：仍一条、历史追加），
 * 逐表对账不多不少；失败路径锚<b>坑二工具层</b>： 坏域名渠道被 Sandbox 拦、{@code tool_invocations} 落 {@code
 * success=false}、任务本身仍 success； 好任务随后照常——失败不拖垮调度器。
 *
 * <p>与 27 节 {@code HumanTriggerFlowIntegrationTest}
 * 的分工：那节验<b>人推</b>一次对话穿八站；本节验<b>钟推</b>——同一引擎换触发头（技 §8.5），外加 task 两表同步与会话复用这两个钟推特有面。
 *
 * <p>hermetic：root/db 指临时工作区（沙箱 file 白名单自动跟，坑七）、域名白名单经 {@code @Primary} tools 自备（open-meteo +
 * 本地接收端， <b>不含</b> evil.example.com——失败路径专用，24 节坑先例）。
 *
 * <p>跑法：
 *
 * <pre>
 * source ~/.zshrc && mvn -pl yokeos-boot -am test \
 *     -Dgroups=integration -DexcludedGroups= -Dtest='SchedulerNotifyFlowIntegrationTest'
 * </pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulerNotifyFlowIntegrationTest {

  private static final String AGENT_GOOD = "weather-push-agent";

  private static final String TASK_GOOD = AGENT_GOOD + ":morning";

  private static final String AGENT_BAD = "bad-webhook-agent";

  private static final String TASK_BAD = AGENT_BAD + ":probe";

  private static final String CLOCK_SID = "scheduler:scheduler:" + AGENT_GOOD;

  @TempDir static Path workspace;

  static HttpServer webhookReceiver;

  static final List<String> receivedBodies = new ArrayList<>();

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired TestRestTemplate rest;

  @Autowired AgentScheduler scheduler;

  @Autowired ScheduledTaskRepository scheduledTaskRepository;

  @Autowired TaskExecutionRepository taskExecutionRepository;

  @Autowired com.yokeos.storage.LlmCallRepository llmCallRepository;

  @Autowired ToolInvocationRepository toolInvocationRepository;

  /** 真上下文 tools 覆盖（24/27 节坑先例）：白名单含 open-meteo + 本地接收端、不含 evil——失败路径由缺席造出。 */
  @TestConfiguration
  static class ToolWhitelistConfig {

    @Bean
    @Primary
    Map<String, YokeTool> itTools() {
      var sandbox =
          new com.yokeos.tool.sandbox.WhitelistSandbox(
              new com.yokeos.tool.sandbox.SandboxProperties(
                  List.of(workspace.toAbsolutePath().toString()),
                  List.of(),
                  List.of("localhost", "127.0.0.1", "api.open-meteo.com")));
      // 记忆走独立实例但同一路径文件（workspace/memory），与上下文 memoryService Bean 读写同一 MEMORY.md
      var memoryTools =
          new com.yokeos.memory.builtin.MemoryTools(
              new com.yokeos.memory.MemoryServiceImpl(
                  new com.yokeos.memory.MarkdownMemoryStore(
                      workspace.resolve("memory"), 4000, sandbox)));
      com.yokeos.tool.ToolRegistry registry = new com.yokeos.tool.ToolRegistry();
      registry.registerAnnotated(new com.yokeos.tool.builtin.HttpTools(sandbox));
      registry.registerAnnotated(memoryTools);
      registry.register(
          new com.yokeos.tool.NotifyTools(
              Map.of("webhook", new com.yokeos.tool.notify.WebhookNotifyAdapter()), sandbox));
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
          synchronized (receivedBodies) {
            receivedBodies.add(new String(exchange.getRequestBody().readAllBytes()));
          }
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    webhookReceiver.start();
    String receiverUrl = "http://127.0.0.1:" + webhookReceiver.getAddress().getPort() + "/hook";

    // 好 Agent：27 节天气穿搭推送 Agent + schedules 段（远期 cron，runNow 驱动）
    Path goodDir = workspace.resolve("agents").resolve(AGENT_GOOD);
    Files.createDirectories(goodDir);
    Files.writeString(
        goodDir.resolve("AGENT.md"),
        "---\n"
            + "name: "
            + AGENT_GOOD
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
            + "schedules:\n"
            + "  - id: morning\n"
            + "    cron: \"0 0 0 1 1 *\"\n"
            + "    zone: Asia/Shanghai\n"
            + "    message: 查一下北京现在的天气，给出穿搭建议，并把建议推送到群里。"
            + "无论此前对话历史如何（包括之前已经推送过），本次都要重新完整执行一遍。\n"
            + "---\n"
            + "查天气用 open-meteo：https://api.open-meteo.com/v1/forecast?latitude=39.9&"
            + "longitude=116.4&current=temperature_2m。\n");

    // 坏 Agent：notify 渠道指白名单外域名——Sandbox 拦截的失败路径探针（坑二工具层）
    Path badDir = workspace.resolve("agents").resolve(AGENT_BAD);
    Files.createDirectories(badDir);
    Files.writeString(
        badDir.resolve("AGENT.md"),
        "---\n"
            + "name: "
            + AGENT_BAD
            + "\n"
            + "identity:\n"
            + "  agent_name: 坏渠道探针\n"
            + "  prompt: 你是推送探针。被要求推送时必须调用 notify（channel 填 evil-im，只调一次），"
            + "推送失败也要简短汇报结果，不要重试。\n"
            + "provider:\n"
            + "  name: deepseek\n"
            + "  model: deepseek-flash\n"
            + "tools:\n"
            + "  - notify\n"
            + "notify:\n"
            + "  channels:\n"
            + "    - name: evil-im\n"
            + "      type: webhook\n"
            + "      config:\n"
            + "        url: http://evil.example.com/hook\n"
            + "schedules:\n"
            + "  - id: probe\n"
            + "    cron: \"0 0 0 1 1 *\"\n"
            + "    zone: Asia/Shanghai\n"
            + "    message: 把『钟推失败路径探针』这句话推送到群里。"
            + "无论此前对话历史如何，本次都要重新执行。\n"
            + "---\n"
            + "推送渠道只有一个：evil-im。\n");
  }

  @AfterAll
  static void stopReceiver() {
    if (webhookReceiver != null) {
      webhookReceiver.stop(0);
    }
  }

  @Test
  @DisplayName("钟推全链_两次触发会话复用逐表对账_坏渠道失败不拖垮调度器")
  void scheduledFlow_sessionReusedAndReconciled_failureIsolated() throws Exception {
    // ① 钟推第一次：跑完「查天气 → 推送」，逐表对账
    scheduler.runNow(TASK_GOOD);
    int historyAfterFirst = clockHistorySize();
    assertTrue(historyAfterFirst >= 4, "钟推会话历史至少 4 条（实际: " + historyAfterFirst + "）");
    assertGoodRunAccounts(1, 1);

    // ② 第二次触发——会话复用（坑一：sessions 仍一条、历史追加变长，不冒第二条）
    scheduler.runNow(TASK_GOOD);
    int historyAfterSecond = clockHistorySize();
    assertTrue(
        historyAfterSecond > historyAfterFirst,
        "第二次触发应追加在同一会话（" + historyAfterFirst + " → " + historyAfterSecond + "）");
    assertGoodRunAccounts(2, 2);
    synchronized (receivedBodies) {
      assertTrue(receivedBodies.size() >= 2, "接收端应收到两次推送（实际: " + receivedBodies.size() + "）");
    }

    // ③ 失败路径（坑二工具层）：坏域名渠道被 Sandbox 拦、notify 落 success=false、任务本身仍 success
    scheduler.runNow(TASK_BAD);
    var badRows = toolInvocationRepository.findBySessionId("scheduler:scheduler:" + AGENT_BAD);
    long badNotify = badRows.stream().filter(r -> "notify".equals(r.getToolName())).count();
    assertTrue(badNotify >= 1, "坏渠道 notify 至少一条留痕（实际: " + dump(badRows) + "）");
    assertTrue(
        badRows.stream()
            .filter(r -> "notify".equals(r.getToolName()))
            .allMatch(r -> !Boolean.TRUE.equals(r.getSuccess())),
        "坏渠道 notify 必须全部失败：" + dump(badRows));
    assertTrue(
        badRows.stream()
            .filter(r -> "notify".equals(r.getToolName()))
            .allMatch(
                r ->
                    r.getErrorMessage() != null
                        && r.getErrorMessage().contains("evil.example.com")),
        "失败原因应含被拒域名（人话错误，24 节口径）：" + dump(badRows));
    assertEquals(
        "success", scheduledTask(TASK_BAD).getLastStatus(), "工具层失败不上抛——任务本身应 success（坑二语义分层）");

    // ④ 调度器不死：好任务第三次照常执行成功。账面锚执行（run_count/last_status）+ 引擎真跑（新
    // llm_calls）；物理推送不再断言第三次——坑九：会话复用下历史新鲜（两次推送就在几秒前的历史里），
    // 真模型可能判定「刚推过」不再重推，这是会话复用的真实行为不是调度器故障（31 节日跑间隔一天，此象弱化）。
    final long llmBefore = llmCallCountOf(CLOCK_SID);
    scheduler.runNow(TASK_GOOD);
    assertEquals(3, scheduledTask(TASK_GOOD).getRunCount(), "失败没有拖垮调度器");
    assertEquals("success", scheduledTask(TASK_GOOD).getLastStatus());
    List<TaskExecution> thirdRun = executionsOf(TASK_GOOD);
    assertEquals(3, thirdRun.size(), "第三次执行留第三行历史");
    assertTrue(thirdRun.get(thirdRun.size() - 1).isSuccess(), "第三次执行成功");
    assertTrue(llmCallCountOf(CLOCK_SID) > llmBefore, "第三次触发引擎真跑（新 llm_calls 落账）");
  }

  /** 钟推会话的 llm_calls 计数（按 sessionId 过滤，坑一族：不看全表）。 */
  private long llmCallCountOf(String sessionId) {
    return llmCallRepository.findAll().stream()
        .filter(l -> sessionId.equals(l.getSessionId()))
        .count();
  }

  /** 好任务第 {@code n} 次触发后的账目：run_count、执行历史行数、涉外工具恰量。 */
  private void assertGoodRunAccounts(int expectedRunCount, int expectedNotifyCount) {
    ScheduledTask task = scheduledTask(TASK_GOOD);
    assertEquals(expectedRunCount, task.getRunCount(), "run_count 应同步自增");
    assertEquals("success", task.getLastStatus());
    List<TaskExecution> history = executionsOf(TASK_GOOD);
    assertEquals(expectedRunCount, history.size(), "执行历史一次一行");
    assertTrue(history.stream().allMatch(TaskExecution::isSuccess));

    var rows = toolInvocationRepository.findBySessionId(CLOCK_SID);
    long httpGet = rows.stream().filter(r -> "http_get".equals(r.getToolName())).count();
    long notify = rows.stream().filter(r -> "notify".equals(r.getToolName())).count();
    assertTrue(httpGet >= expectedRunCount, "每次触发至少一次 http_get（实际: " + dump(rows) + "）");
    assertEquals(expectedNotifyCount, notify, "notify 每次恰一次——推送不多不少（实际: " + dump(rows) + "）");
    assertTrue(
        rows.stream().allMatch(r -> Boolean.TRUE.equals(r.getSuccess())),
        "涉外调用都必须成功：" + dump(rows));
    assertTrue(
        history.stream().allMatch(h -> CLOCK_SID.equals(h.getSessionId())), "执行历史关联钟推会话（三元组口径）");
  }

  /** 钟推会话的消息条数（REST 面——26 节端点，与人推同一查询口径）。 */
  private int clockHistorySize() throws Exception {
    JsonNode session = dataOf(get("/api/v1/sessions/" + CLOCK_SID));
    return session.get("messages").size();
  }

  // —— helpers ——

  private ScheduledTask scheduledTask(String taskId) {
    return scheduledTaskRepository.findById(taskId).orElseThrow();
  }

  private List<TaskExecution> executionsOf(String taskId) {
    return taskExecutionRepository.findAll().stream()
        .filter(e -> taskId.equals(e.getTaskId()))
        .toList();
  }

  private String dump(List<com.yokeos.storage.ToolInvocation> rows) {
    return rows.stream().map(r -> r.getToolName() + "(" + r.getSuccess() + ")").toList().toString();
  }

  private ResponseEntity<String> get(String path) {
    return rest.getForEntity(path, String.class);
  }

  private JsonNode dataOf(ResponseEntity<String> response) throws Exception {
    assertEquals(
        200, response.getStatusCode().value(), "HTTP 应 200（body: " + response.getBody() + "）");
    JsonNode body = mapper.readTree(response.getBody());
    assertEquals(0, body.get("code").asInt(), "统一信封 code 应 0（body: " + response.getBody() + "）");
    return body.get("data");
  }
}
