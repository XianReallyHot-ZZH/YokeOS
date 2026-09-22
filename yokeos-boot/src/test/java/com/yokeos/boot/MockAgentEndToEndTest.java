package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yokeos.storage.LlmCallRepository;
import com.yokeos.storage.ToolInvocationRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 真启动整台服务的无 key 全链 E2E（第 27 节，gate 内）：{@code @SpringBootTest} 起真 HTTP 端口 + SQLite， mock provider
 * 常挂（{@code YokeosRuntime} 内置保留名）驱动确定性 ReAct——从 HTTP 建会话、发「记住…」、到查回 会话/记忆/工具/审计，跟人手动点一遍完全一样，只是自动化了。
 *
 * <p>hermetic 三件（25/26 节坑先例）：{@code yokeos.root} 指临时工作区（AGENT.md 手写、沙箱 file 白名单自动跟 上——坑七解法）、{@code
 * yokeos.db.dir} 指临时目录（不碰工作区真库）、测试清单不注入任何真实 provider（坑五： mock 一个就够）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MockAgentEndToEndTest {

  private static final String AGENT = "mock-agent";

  private static final String FACT = "我喜欢喝美式咖啡";

  @TempDir static Path workspace;

  @Autowired TestRestTemplate rest;

  @Autowired LlmCallRepository llmCallRepository;

  @Autowired ToolInvocationRepository toolInvocationRepository;

  @DynamicPropertySource
  static void pinWorkspace(DynamicPropertyRegistry registry) {
    registry.add("yokeos.root", () -> workspace.toAbsolutePath().toString());
    registry.add("yokeos.db.dir", () -> workspace.resolve("db").toAbsolutePath().toString());
  }

  @BeforeAll
  static void writeWorkspace() throws Exception {
    Files.createDirectories(workspace.resolve("db")); // SQLite 不自动建父目录（SQLITE_CANTOPEN 同族）
    Files.writeString(workspace.resolve("AGENTS.md"), "测试工作区约定");
    Files.writeString(workspace.resolve("SOUL.md"), "你是简洁实用的助手");
    Files.writeString(workspace.resolve("USER.md"), "无特殊偏好");
    Path agentDir = workspace.resolve("agents").resolve(AGENT);
    Files.createDirectories(agentDir);
    Files.writeString(
        agentDir.resolve("AGENT.md"),
        "---\n"
            + "name: "
            + AGENT
            + "\n"
            + "identity:\n"
            + "  agent_name: 自测助手\n"
            + "  prompt: 你是测试助手。\n"
            + "provider:\n"
            + "  name: mock\n"
            + "  model: mock-model\n"
            + "tools:\n"
            + "  - save_memory\n"
            + "  - recall_memory\n"
            + "---\n"
            + "无正文要求。\n");
  }

  @Test
  @DisplayName("真服务HTTP全链_建会话发记住消息_会话记忆工具审计全查得回")
  void httpEndToEnd_withMockAgent_sessionMemoryToolsAuditAllVisible() {
    String base = "/api/v1";

    // ① 建会话（web 入口三元组拼接单点在 SessionIds）
    ResponseEntity<Map> created =
        rest.postForEntity(base + "/sessions", body(Map.of("profile", AGENT)), Map.class);
    assertEquals(HttpStatus.OK, created.getStatusCode());
    String sessionId = dataOf(created).get("sessionId").toString();
    assertEquals("web:default:" + AGENT, sessionId, "web 渠道三元组拼接口径（26 节）");

    // ② 发「记住…」——mock 驱动两轮 ReAct + 恰一次 save_memory
    ResponseEntity<Map> replied =
        rest.postForEntity(
            base + "/sessions/" + sessionId + "/messages",
            body(Map.of("content", "记住：" + FACT)),
            Map.class);
    assertEquals(HttpStatus.OK, replied.getStatusCode());
    assertNotNull(dataOf(replied));

    // ③ 会话查得回：历史含 user / assistant×2 / tool(save_memory)
    ResponseEntity<Map> session = rest.getForEntity(base + "/sessions/" + sessionId, Map.class);
    assertEquals(HttpStatus.OK, session.getStatusCode());
    List<Map<String, Object>> messages =
        (List<Map<String, Object>>) dataOf(session).get("messages");
    assertTrue(messages.size() >= 4, "完整历史至少 4 条（实际: " + messages.size() + "）");
    assertTrue(
        messages.stream()
            .anyMatch(
                m -> AGENT.equals(m.get("toolName")) || "save_memory".equals(m.get("toolName"))),
        "历史含 save_memory 工具结果行");

    // ④ 长期记忆查得回（save_memory 真写了临时工作区 MEMORY.md；data 是全文 String——26 节「原样」口径）
    ResponseEntity<Map> memory = rest.getForEntity(base + "/memory", Map.class);
    assertEquals(HttpStatus.OK, memory.getStatusCode());
    assertTrue(
        String.valueOf(memory.getBody().get("data")).contains("美式咖啡"),
        "GET /memory 应含刚写入的事实（实际: " + memory.getBody().get("data") + "）");

    // ⑤ 工具清单查得回（data 是工具数组）
    ResponseEntity<Map> tools = rest.getForEntity(base + "/tools", Map.class);
    assertEquals(HttpStatus.OK, tools.getStatusCode());
    assertTrue(
        String.valueOf(tools.getBody().get("data")).contains("save_memory"),
        "GET /tools 应含 save_memory");

    // ⑥ 会话列表查得回（26 节补位的第 19 端点；data 是摘要数组）
    ResponseEntity<Map> list = rest.getForEntity(base + "/sessions", Map.class);
    assertEquals(HttpStatus.OK, list.getStatusCode());
    assertTrue(String.valueOf(list.getBody().get("data")).contains(sessionId), "列表应含这条会话");

    // ⑦ 审计对账（坑四：按 sessionId 过滤真库）——llm_calls 恰 2、tool_invocations 恰 1
    long llmCount =
        llmCallRepository.findAll().stream()
            .filter(l -> sessionId.equals(l.getSessionId()))
            .count();
    assertEquals(2, llmCount, "llm_calls 应恰 2 条");
    List<?> toolRows = toolInvocationRepository.findBySessionId(sessionId);
    assertEquals(1, toolRows.size(), "tool_invocations 应恰 1 条");
  }

  private static org.springframework.http.HttpEntity<Map<String, String>> body(
      Map<String, String> payload) {
    return new org.springframework.http.HttpEntity<>(payload, jsonHeaders());
  }

  private static org.springframework.http.HttpHeaders jsonHeaders() {
    org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
    headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
    return headers;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> dataOf(ResponseEntity<Map> response) {
    return (Map<String, Object>) response.getBody().get("data");
  }
}
