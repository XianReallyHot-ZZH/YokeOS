package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yokeos.core.agent.AgentScheduler;
import com.yokeos.core.agent.PromptBuilder;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.Session;
import com.yokeos.core.session.SessionManager;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.storage.LlmCallRepository;
import com.yokeos.storage.ScheduledTask;
import com.yokeos.storage.ScheduledTaskRepository;
import com.yokeos.storage.TaskExecution;
import com.yokeos.storage.TaskExecutionRepository;
import com.yokeos.storage.ToolInvocationRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
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
 * 多 Agent 并存三隔离（第 28 节，mock 驱动、gate 内）：三个差异明显的 Profile 同实例——工具 / 会话 / 定时三条边界各验一条。
 *
 * <p><b>工具隔离断言在 PromptBuilder 组装面</b>（实施修正，记验收报告）：本仓 ToolExecutor 注入的是全量注册表、不按 Profile 过滤
 * （第一阶段语义：隔离边界 = LLM 可见的点名清单），故账目面看不到「未注册」失败——正确断言是 {@code
 * PromptBuilder.build(...).availableTools()} 只含 Profile 点名的工具。
 *
 * <p><b>定时隔离探针</b>（坑八修正造法）：坏 provider 名会被 AgentLoader 启动校验整目录跳过，故用 {@code @Primary}
 * 映射表挂一个「名字过校验、调用必炸」的 {@link FailingChatModel}（名 {@code boom}）——Provider 层失败穿透 process、被 execute
 * 兜底记 {@code task_executions success=false}（坑二 Provider 层），随后好任务照常成功即失败隔离证据。
 *
 * <p>hermetic 三件同 27 节：root/db 指临时工作区、清单零注入、mock 内置常挂（{@code boom} 仅本类上下文存在）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiAgentIsolationTest {

  private static final String AGENT_A = "multi-a";

  private static final String AGENT_B = "multi-b";

  private static final String AGENT_C = "multi-c";

  private static final String TASK_A = AGENT_A + ":daily";

  private static final String TASK_C = AGENT_C + ":daily";

  private static final String FACT_A = "甲号探针：多Agent甲的记忆事实";

  private static final String FACT_B = "乙号探针：多Agent乙的消息";

  @TempDir static Path workspace;

  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired TestRestTemplate rest;

  @Autowired PromptBuilder promptBuilder;

  @Autowired ProfileRegistry profileRegistry;

  @Autowired SessionManager sessionManager;

  @Autowired AgentScheduler scheduler;

  @Autowired LlmCallRepository llmCallRepository;

  @Autowired ToolInvocationRepository toolInvocationRepository;

  @Autowired ScheduledTaskRepository scheduledTaskRepository;

  @Autowired TaskExecutionRepository taskExecutionRepository;

  /** Provider 映射覆盖（定时隔离探针）：mock + boom（调用必炸）——校验面与运行面同表，boom 过启动校验。 */
  @TestConfiguration
  static class ProviderMapConfig {

    @Bean
    @Primary
    Map<String, ChatModel> testProviderMap() {
      return Map.of(
          "mock", new com.yokeos.provider.MockChatModel(), "boom", new FailingChatModel());
    }
  }

  /** Provider 层故障替身（坑八修正造法）：过启动校验（在 keySet 里）、任何调用必炸——驱动 task_executions 失败路径。 */
  static class FailingChatModel implements ChatModel {

    @Override
    public org.springframework.ai.chat.model.ChatResponse call(
        org.springframework.ai.chat.prompt.Prompt prompt) {
      throw new IllegalStateException("boom provider 故障探针：永远不可用");
    }
  }

  @DynamicPropertySource
  static void pinWorkspace(DynamicPropertyRegistry registry) {
    registry.add("yokeos.root", () -> workspace.toAbsolutePath().toString());
    registry.add("yokeos.db.dir", () -> workspace.resolve("db").toAbsolutePath().toString());
  }

  @BeforeAll
  static void writeWorkspace() throws IOException {
    Files.createDirectories(workspace.resolve("db")); // SQLite 不自动建父目录（SQLITE_CANTOPEN 同族）
    Files.writeString(workspace.resolve("AGENTS.md"), "测试工作区约定");
    Files.writeString(workspace.resolve("SOUL.md"), "你是简洁实用的助手");
    Files.writeString(workspace.resolve("USER.md"), "无特殊偏好");
    // A：含记忆工具 + 远期定时（好任务）
    writeAgent(
        AGENT_A,
        "mock",
        List.of("save_memory", "recall_memory"),
        """
        每天例行记录一句话。
        """);
    // B：点名只有 read_file（不含 save_memory）——工具隔离的对照组
    writeAgent(AGENT_B, "mock", List.of("read_file"), """
        只读文件的助手。
        """);
    // C：boom provider（调用必炸）+ 远期定时——定时隔离的故障源
    writeAgent(AGENT_C, "boom", List.of(), """
        故障探针 Agent。
        """);
  }

  private static void writeAgent(String name, String provider, List<String> tools, String body)
      throws IOException {
    Path agentDir = workspace.resolve("agents").resolve(name);
    Files.createDirectories(agentDir);
    StringBuilder frontmatter = new StringBuilder("---\n");
    frontmatter.append("name: ").append(name).append('\n');
    frontmatter.append("identity:\n  agent_name: ").append(name).append("\n  prompt: 你是测试助手。\n");
    frontmatter.append("provider:\n  name: ").append(provider).append("\n  model: probe-model\n");
    if (!tools.isEmpty()) {
      frontmatter.append("tools:\n");
      tools.forEach(t -> frontmatter.append("  - ").append(t).append('\n'));
    }
    frontmatter
        .append("schedules:\n")
        .append("  - id: daily\n")
        .append("    cron: \"0 0 0 1 1 *\"\n")
        .append("    zone: Asia/Shanghai\n")
        .append("    message: ")
        .append(name)
        .append(" 的例行巡检：记录一句话即完成。\n");
    frontmatter.append("---\n").append(body);
    Files.writeString(agentDir.resolve("AGENT.md"), frontmatter.toString());
  }

  @Test
  @DisplayName("工具隔离_PromptBuilder只带Profile点名工具")
  void toolIsolation_promptBuilderPresentsOnlyProfileTools() {
    Profile profileA = profileRegistry.get(AGENT_A).orElseThrow();
    Profile profileB = profileRegistry.get(AGENT_B).orElseThrow();
    Session sessionA = sessionManager.getOrCreate("web", "default", AGENT_A);
    Session sessionB = sessionManager.getOrCreate("web", "default", AGENT_B);

    List<String> toolsA =
        promptBuilder.build(sessionA, profileA).availableTools().stream()
            .map(YokeTool::getName)
            .toList();
    List<String> toolsB =
        promptBuilder.build(sessionB, profileB).availableTools().stream()
            .map(YokeTool::getName)
            .toList();

    assertTrue(toolsA.contains("save_memory"), "A 的可用清单应含 save_memory（实际: " + toolsA + "）");
    assertFalse(
        toolsB.contains("save_memory"),
        "B（点名只有 read_file）的可用清单不应含 save_memory（实际: " + toolsB + "）——LLM 可见面即隔离边界");
    assertTrue(toolsB.contains("read_file"), "B 的可用清单应含自己点名的 read_file（实际: " + toolsB + "）");
  }

  @Test
  @DisplayName("会话隔离_两Agent各自会话互不串")
  void sessionIsolation_twoAgentsOwnSessions() throws Exception {
    String sidA = invoke(AGENT_A, "记住：" + FACT_A);
    String sidB = invoke(AGENT_B, "记住：" + FACT_B);

    assertEquals("web:default:" + AGENT_A, sidA, "A 会话三元组口径");
    assertEquals("web:default:" + AGENT_B, sidB, "B 会话三元组口径");
    assertFalse(sidA.equals(sidB), "两个 Agent 的会话 id 必须不同");

    // 各自历史只含自己的消息（按 sessionId 过滤，坑一族：不看全表）
    String historyA = dataOf(get("/api/v1/sessions/" + sidA)).toString();
    String historyB = dataOf(get("/api/v1/sessions/" + sidB)).toString();
    assertTrue(historyA.contains("甲号探针"), "A 历史应含 A 的事实");
    assertFalse(historyA.contains("乙号探针"), "A 历史不应串入 B 的消息");
    assertTrue(historyB.contains("乙号探针"), "B 历史应含 B 的消息");
    assertFalse(historyB.contains("甲号探针"), "B 历史不应串入 A 的事实");

    // 列表两态并见（同一实例多 Agent 并存）
    List<String> ids =
        StreamSupport.stream(dataOf(get("/api/v1/sessions")).spliterator(), false)
            .map(n -> n.get("sessionId").asText())
            .toList();
    assertTrue(ids.contains(sidA) && ids.contains(sidB), "列表应同时见 A/B 会话（实际: " + ids + "）");
  }

  @Test
  @DisplayName("定时隔离_C坏provider失败落账_A好任务随后照常成功")
  void scheduleIsolation_badProviderTaskFails_goodTaskStillRuns() {
    // ① C 任务：boom 调用必炸 → execute 兜底记失败（坑二 Provider 层）
    scheduler.runNow(TASK_C);

    List<TaskExecution> boomTaskHistory = executionsOf(TASK_C);
    assertEquals(1, boomTaskHistory.size(), "C 失败执行恰留一行历史");
    assertFalse(boomTaskHistory.get(0).isSuccess(), "C 执行应失败（Provider 层）");
    assertTrue(
        boomTaskHistory.get(0).getErrorMessage() != null
            && boomTaskHistory.get(0).getErrorMessage().contains("boom"),
        "失败原因应含探针字样（实际: " + boomTaskHistory.get(0).getErrorMessage() + "）");
    assertEquals("failed", scheduledTask(TASK_C).getLastStatus(), "C 任务 last_status=failed");

    // 失败那一半的账：C 的钟推会话在 llm_calls 落 success=false（审计与人推失败无区别）
    String clockSidC = "scheduler:scheduler:" + AGENT_C;
    assertTrue(
        llmCallRepository.findAll().stream()
            .anyMatch(
                l -> clockSidC.equals(l.getSessionId()) && !Boolean.TRUE.equals(l.getSuccess())),
        "C 的失败调用应落 llm_calls success=false");

    // ② A 任务随后照常成功——C 的失败没拖垮调度器（失败隔离的多 Agent 版）
    scheduler.runNow(TASK_A);

    List<TaskExecution> goodTaskHistory = executionsOf(TASK_A);
    assertEquals(1, goodTaskHistory.size(), "A 执行恰留一行历史");
    assertTrue(goodTaskHistory.get(0).isSuccess(), "A 执行应成功（失败未外溢）");
    assertEquals("success", scheduledTask(TASK_A).getLastStatus());
    // A 的钟推会话真写进记忆（mock 驱动 save_memory 走真实路径）
    assertTrue(
        toolInvocationRepository.findBySessionId("scheduler:scheduler:" + AGENT_A).stream()
            .anyMatch(
                r -> "save_memory".equals(r.getToolName()) && Boolean.TRUE.equals(r.getSuccess())),
        "A 的钟推会话应有成功的 save_memory");
  }

  // —— helpers ——

  private List<TaskExecution> executionsOf(String taskId) {
    return taskExecutionRepository.findAll().stream()
        .filter(e -> taskId.equals(e.getTaskId()))
        .toList();
  }

  private ScheduledTask scheduledTask(String taskId) {
    return scheduledTaskRepository.findById(taskId).orElseThrow();
  }

  private String invoke(String agent, String content) throws Exception {
    String sessionId =
        dataOf(post("/api/v1/sessions", "{\"profile\":\"" + agent + "\"}"))
            .get("sessionId")
            .asText();
    post("/api/v1/sessions/" + sessionId + "/messages", "{\"content\":\"" + content + "\"}");
    return sessionId;
  }

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
    assertEquals(0, body.get("code").asInt(), "统一信封 code 应 0（body: " + response.getBody() + "）");
    return body.get("data");
  }
}
