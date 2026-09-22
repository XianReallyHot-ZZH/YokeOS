package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 动态管理集成冒烟（第 30 节 T018，{@code @Tag("integration")}——真装配上下文含 WorkspaceWatcher 真线程）： 免重启闭环（create →
 * 不重启列表可见 → PUT → invoke → DELETE → 归档实证）+ 真丢目录 Watcher 拾取（轮询断言， 容忍拷贝竞态收敛）+ generate 真模型（assumeTrue
 * 真 key，缺 key 跳过不失败；草稿直投 create 是 SC-003 闭环锚，analyze M1）。 闭环用内置 mock provider（MockChatModel，无 key
 * 无花费）；workspace 指向 TempDir（@DynamicPropertySource， 26 节 db.dir 自钉同思路）。
 */
@Tag("integration")
@AutoConfigureMockMvc
@SpringBootTest(properties = "yokeos.db.dir=target")
class AgentLifecycleIntegrationTest {

  @TempDir static Path workspace;

  @DynamicPropertySource
  static void workspaceRoot(DynamicPropertyRegistry registry) {
    registry.add("yokeos.root", () -> workspace.toString());
  }

  @Autowired private MockMvc mockMvc;

  /** mock provider 的合法 AGENT.md（MockChatModel 内置保留名——invoke 全链路不花钱）。 */
  private static String markdownOf(String name, String description) {
    // 先整体括起再 formatted——formatted 只作用于紧邻字面量，分段拼接会让前段 %s 原样落盘（实测坑）
    return ("---\nname: %s\ndescription: %s\nidentity:\n  agent_name: %s\n  prompt: 你是助手\n"
            + "provider:\n  name: mock\n  model: mock-chat\ntools:\n  - read_file\n---\n%s 的任务指令正文")
        .formatted(name, description, name, name);
  }

  private ResultActions postJson(String uri, String body) throws Exception {
    return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body));
  }

  /** 轮询断言（Watcher 真线程拾取的时序容错，SC-002：上限 10 秒）。 */
  private static void awaitAssert(Duration limit, java.util.function.BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + limit.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(200);
    }
    assertTrue(condition.getAsBoolean(), "轮询超时（10s 内未达成预期状态）");
  }

  /**
   * 列表轮询探针：取 data 数组按 name 比对——<b>不抛断言</b>（jsonPath exists 失败抛 AssertionError 是 Error 不进
   * catch，轮询第一圈就炸出测试、从未真正等待 Watcher——30 节实施实证坑）。
   */
  private boolean listContains(String name) {
    try {
      String body =
          mockMvc.perform(get("/api/v1/agents")).andReturn().getResponse().getContentAsString();
      return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).path("data").isArray()
          && java.util.stream.StreamSupport.stream(
                  new com.fasterxml.jackson.databind.ObjectMapper()
                      .readTree(body)
                      .path("data")
                      .spliterator(),
                  false)
              .anyMatch(node -> name.equals(node.path("name").asText()));
    } catch (Exception e) {
      return false;
    }
  }

  @Test
  @DisplayName("免重启闭环：create 后不重启列表即见 → PUT 生效 → invoke → DELETE 归档实证")
  void lifecycleClosedLoopWithoutRestart() throws Exception {
    // ① create → 不重启 GET 列表立即可见（SC-001）
    postJson(
            "/api/v1/agents",
            "{\"name\":\"loop-agent\",\"agentMarkdown\":"
                + quote(markdownOf("loop-agent", "闭环验证"))
                + "}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("loop-agent"));
    assertTrue(listContains("loop-agent"), "create 返回 200 后不重启，列表应立即可见");

    // ② PUT 覆写即时生效
    mockMvc
        .perform(
            put("/api/v1/agents/loop-agent")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentMarkdown\":" + quote(markdownOf("loop-agent", "改后的描述")) + "}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/agents/loop-agent"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.description").value("改后的描述"));

    // ③ invoke（mock provider 全链路，不花钱）
    postJson("/api/v1/agents/loop-agent/invoke", "{\"content\":\"你好\"}").andExpect(status().isOk());

    // ④ DELETE → 注销定时 → 移索引 → 归档目录实证（SC-004）
    mockMvc.perform(delete("/api/v1/agents/loop-agent")).andExpect(status().isOk());
    assertTrue(
        Files.isDirectory(workspace.resolve("archive/loop-agent")), "归档目录应出现在 .yokeos/archive/");
    mockMvc.perform(get("/api/v1/agents/loop-agent")).andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("丢目录即上线：serve 运行中 cp 进 agents/ → 轮询 ≤10s 列表出现；手工删 → 轮询消失")
  void droppedDirectoryIsPickedUpByWatcher() throws Exception {
    Path agentDir = workspace.resolve("agents/dropped-agent");
    Files.createDirectories(agentDir);
    Files.writeString(agentDir.resolve("AGENT.md"), markdownOf("dropped-agent", "丢目录验证"));

    awaitAssert(Duration.ofSeconds(10), () -> listContains("dropped-agent"));

    // 手工删对称：Watcher 注销（不归档）
    deleteRecursively(agentDir);
    awaitAssert(Duration.ofSeconds(10), () -> !listContains("dropped-agent"));
    assertTrue(!Files.isDirectory(workspace.resolve("archive/dropped-agent")), "手工删不归档（FR-010）");
  }

  @Test
  @DisplayName("generate 真模型：草稿可解析且直投 create 成功（assumeTrue 真 key，缺 key 跳过）")
  void generateDraftFeedsCreateWithRealModel() throws Exception {
    String key = System.getenv("DEEPSEEK_API_KEY");
    // Maven ${env.X} 透传失败会保留字面占位（非真值），一并视为无 key（19 节坑的守卫形态）
    org.junit.jupiter.api.Assumptions.assumeTrue(
        key != null && !key.isBlank() && !key.contains("${env."));

    String draftEnvelope =
        mockMvc
            .perform(
                post("/api/v1/agents/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sentence\":\"每天早上九点查北京天气并给我一句穿搭建议\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.agentMarkdown").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    com.fasterxml.jackson.databind.JsonNode data =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(draftEnvelope).path("data");
    String agentMarkdown = data.path("agentMarkdown").asText();
    assertTrue(agentMarkdown.contains("---"), "草稿应是 AGENT.md 形态");

    // 人在环的机器模拟：改写草稿 frontmatter 的 name 为固定合法名（预览本来就是让人改的）
    String canonical = agentMarkdown.replaceFirst("(?m)^name:.*$", "name: gen-smoke-agent");
    postJson(
            "/api/v1/agents",
            "{\"name\":\"gen-smoke-agent\",\"agentMarkdown\":" + quote(canonical) + "}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("gen-smoke-agent"));

    mockMvc.perform(delete("/api/v1/agents/gen-smoke-agent")).andExpect(status().isOk()); // 清场
  }

  /** JSON 字符串字面量化（markdown 含换行与引号）。 */
  private static String quote(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  private static void deleteRecursively(Path dir) throws IOException {
    try (var paths = Files.walk(dir)) {
      paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.delete(p);
                } catch (IOException e) {
                  throw new java.io.UncheckedIOException(e);
                }
              });
    }
  }
}
