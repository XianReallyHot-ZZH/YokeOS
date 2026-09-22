package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 黑盒探针（第 27 节拍板③）：打一个<b>已经在跑</b>的 YokeOS 实例（默认 {@code http://localhost:8080}），不自己起服务
 * ——「人推链路三面同源」的黑盒面。31 节两个日跑 Demo 上线后直接复用为线上黑盒探针（{@code -Dyokeos.base-url=http://host:port} 换目标）。
 *
 * <p>类名刻意不带 {@code Test} 后缀——surefire 默认 include 不匹配，<b>不进常规 gate</b>，只在显式 {@code
 * -Dtest=LiveApiProbe} 时运行（参照 {@code LiveApiIT} 同语义；本仓 Checkstyle 禁 IT 连续大写，19 节坑先例）。
 * 每个用例前先探活，服务没起则<b>跳过</b>（不误报失败）。
 *
 * <p>用法：
 *
 * <pre>
 * # 先起服务（测试工作区有 provider: mock 的 mock-agent，无 key）：
 * java -cp ... com.yokeos.cli.YokeOsCli serve --port 8080
 * mvn -pl yokeos-boot -am test -Dtest=LiveApiProbe -Dsurefire.failIfNoSpecifiedTests=false
 * # 打别的实例 / 换 Agent：
 * #   -Dyokeos.base-url=http://host:port  -Dyokeos.test-profile=xxx
 * </pre>
 */
class LiveApiProbe {

  private static final String BASE =
      System.getProperty("yokeos.base-url", "http://localhost:8080") + "/api/v1";

  private static final String PROFILE = System.getProperty("yokeos.test-profile", "mock-agent");

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeEach
  void serviceMustBeUp() {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        reachable(), "服务未在 " + BASE + " 运行——先起 serve，本用例跳过");
  }

  @Test
  @DisplayName("真实调用运行中的服务_建会话发对话再查回会话记忆工具")
  void liveServiceDriveConversationThenQueryBack() throws Exception {
    // ① 健康
    assertEquals("ok", get("/health").get("status").asText());

    // ② 建会话（mock Agent 无 key；每次随机 userId → 会话 id 每跑一次都不同，可反复重跑）
    String userId = "live-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    String sessionId =
        post("/sessions", "{\"profile\":\"" + PROFILE + "\",\"userId\":\"" + userId + "\"}")
            .get("sessionId")
            .asText();
    assertFalse(sessionId.isBlank(), "建会话必须返回 sessionId");

    // ③ 发一条「记住…」（mock provider 驱动一次 save_memory + 两轮 ReAct）
    String fact = "黑盒探针事实-" + userId;
    String reply =
        post("/sessions/" + sessionId + "/messages", "{\"content\":\"记住：" + fact + "\"}")
            .get("reply")
            .asText();
    assertFalse(reply.isBlank(), "答复应非空");

    // ④ 查回：历史含工具行、记忆含事实、工具清单非空、列表见此会话——三面同源的黑盒面
    JsonNode history = get("/sessions/" + sessionId);
    assertTrue(history.get("messages").size() >= 4, "历史应含完整往来（实际: " + history + "）");
    String memory = get("/memory").toString();
    assertTrue(memory.contains(userId), "记忆应含刚写入的事实（实际: " + memory + "）");
    assertTrue(get("/tools").size() > 0, "工具清单应非空");
    boolean listed = false;
    for (JsonNode row : get("/sessions")) {
      listed = listed || sessionId.equals(row.get("sessionId").asText());
    }
    assertTrue(listed, "会话列表应见这条会话");
  }

  // —— helpers ——

  private boolean reachable() {
    try {
      return http.send(
                  HttpRequest.newBuilder(URI.create(BASE + "/health")).GET().build(),
                  HttpResponse.BodyHandlers.ofString())
              .statusCode()
          == 200;
    } catch (Exception e) {
      return false;
    }
  }

  private JsonNode get(String path) throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(BASE + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(
        200, response.statusCode(), "GET " + path + " 应 200（body: " + response.body() + "）");
    return dataOf(response);
  }

  private JsonNode post(String path, String json) throws Exception {
    HttpResponse<String> response =
        http.send(
            HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(
        200, response.statusCode(), "POST " + path + " 应 200（body: " + response.body() + "）");
    return dataOf(response);
  }

  private JsonNode dataOf(HttpResponse<String> response) throws Exception {
    JsonNode body = mapper.readTree(response.body());
    assertEquals(0, body.get("code").asInt(), "统一信封 code 应为 0（body: " + response.body() + "）");
    return body.get("data");
  }
}
