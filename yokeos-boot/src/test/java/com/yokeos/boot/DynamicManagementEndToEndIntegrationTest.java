package com.yokeos.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yokeos.storage.LlmCall;
import com.yokeos.storage.LlmCallRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 动态管理真模型全串联（第 30 节 debug 用例，{@code @Tag("integration")} 显式触发）：一条主线串完 30 节八端点—— generate（真 DeepSeek
 * 出草稿）→ create → 免重启可见 → GET 全文回读 → PUT → <b>invoke（真模型 ReAct 全链路）</b> → workspace tree/file →
 * DELETE 归档，外加 llm_calls 双侧审计断言（generate 的 agent-generation-* 与 invoke 的会话调用）。
 *
 * <p>单 @Test 长链是刻意的：失败时栈直接指到步骤行（debug 定位优先于用例粒度）；步骤序号与注释即走查手册。 真模型两处花钱（generate 一次 + invoke
 * 全链路一次，句子刻意选「无需工具」的激励语——链路最短最稳，不撞 deny-all 沙箱与 webhook 占位坑）； 缺真 key 时整个用例 assumeTrue 跳过不失败（19 节
 * env 透传守卫形态）。
 */
@Tag("integration")
@AutoConfigureMockMvc
@SpringBootTest(properties = "yokeos.db.dir=target")
class DynamicManagementEndToEndIntegrationTest {

  @TempDir static Path workspace;

  @DynamicPropertySource
  static void workspaceRoot(DynamicPropertyRegistry registry) {
    registry.add("yokeos.root", () -> workspace.toString());
  }

  private static final ObjectMapper JSON = new ObjectMapper();

  private static final String AGENT_NAME = "gen-e2e-agent";

  @Autowired private MockMvc mockMvc;

  @Autowired private LlmCallRepository llmCallRepository;

  @Test
  @DisplayName("真模型全串联：一句话→草稿→创建→免重启→PUT→invoke真链路→工作区→删除归档→审计双侧")
  void fullChainWithRealModel() throws Exception {
    String key = System.getenv("DEEPSEEK_API_KEY");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        key != null && !key.isBlank() && !key.contains("${env."), "缺真 DEEPSEEK_API_KEY，跳过");

    // ── S1 · generate：一句话 → 真模型草稿（不落盘不注册）──────────────────────
    String draftEnvelope =
        mockMvc
            .perform(
                post("/api/v1/agents/generate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"sentence\":\"一个简洁的晨间激励语助手，每天早上八点说一句积极的中文激励语，"
                            + "不需要联网查资料，不需要推送到任何渠道\"}"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    JsonNode draftData = JSON.readTree(draftEnvelope).path("data");
    String draft = draftData.path("agentMarkdown").asText();
    assertTrue(draft.startsWith("---"), "S1 草稿应以 frontmatter 开头，实际: " + head(draft));

    // S1b · 不落盘不注册（人在环的机器证据）
    assertFalse(Files.exists(workspace.resolve("agents/" + AGENT_NAME)), "S1b generate 不得落盘");
    assertTrue(agentNames().isEmpty(), "S1b generate 不得注册（列表应为空）");

    // ── S2 · 草稿规范化（人在环的机器模拟：改 name 为合法固定名）─────────────────
    String canonical = draft.replaceFirst("(?m)^name:.*$", "name: " + AGENT_NAME);

    // ── S3 · create：落盘 + 注册，免重启即上线 ──────────────────────────────
    int createStatus =
        mockMvc
            .perform(
                post("/api/v1/agents")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"name\":\""
                            + AGENT_NAME
                            + "\",\"agentMarkdown\":"
                            + quote(canonical)
                            + "}"))
            .andReturn()
            .getResponse()
            .getStatus();
    assertEquals(200, createStatus, "S3 create 应 200（草稿→创建闭环，SC-003）");
    assertTrue(
        Files.isRegularFile(workspace.resolve("agents/" + AGENT_NAME + "/AGENT.md")),
        "S3 AGENT.md 应落盘");

    // ── S4 · 免重启列表可见（SC-001）────────────────────────────────────────
    assertTrue(agentNames().contains(AGENT_NAME), "S4 create 后不重启，列表应立即可见");

    // ── S5 · GET 单个：agentMarkdown 全文回读（编辑回填口径，拍板⑧）──────────────
    String viewEnvelope =
        mockMvc
            .perform(get("/api/v1/agents/" + AGENT_NAME))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    String readBack = JSON.readTree(viewEnvelope).path("data").path("agentMarkdown").asText();
    assertEquals(canonical, readBack, "S5 回读全文应与创建时一致");

    // ── S6 · PUT 覆写即时生效───────────────────────────────────────────────
    String updated = canonical.replaceFirst("(?m)^description:.*$", "description: 已更新的描述");
    int putStatus =
        mockMvc
            .perform(
                put("/api/v1/agents/" + AGENT_NAME)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"agentMarkdown\":" + quote(updated) + "}"))
            .andReturn()
            .getResponse()
            .getStatus();
    assertEquals(200, putStatus, "S6 PUT 应 200");
    String afterPut =
        mockMvc
            .perform(get("/api/v1/agents/" + AGENT_NAME))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertTrue(
        JSON.readTree(afterPut).path("data").path("description").asText().contains("已更新"),
        "S6 覆写应即时生效");

    // ── S7 · invoke：真模型 ReAct 全链路（真 DeepSeek，第二次真调用）─────────────
    String invokeEnvelope =
        mockMvc
            .perform(
                post("/api/v1/agents/" + AGENT_NAME + "/invoke")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"content\":\"给我今天的激励语\"}"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    JsonNode invokeData = JSON.readTree(invokeEnvelope).path("data");
    assertTrue(!invokeData.path("reply").asText().isBlank(), "S7 invoke 真模型应返回非空回复");

    // S7b · llm_calls 双侧审计：generate 的 agent-generation-* 与 invoke 的会话调用都落账（宪法 7）
    List<LlmCall> calls = llmCallRepository.findAll();
    assertTrue(
        calls.stream().anyMatch(c -> c.getSessionId().startsWith("agent-generation-")),
        "S7b generate 调用应落 llm_calls（sessionId 前缀 agent-generation）");
    assertTrue(
        calls.stream()
            .anyMatch(
                c ->
                    c.getSessionId().endsWith(":" + AGENT_NAME)
                        && Boolean.TRUE.equals(c.getSuccess())),
        "S7b invoke 真链路应落 llm_calls 且 success=true");

    // ── S8 · workspace 只读浏览：tree 含该 Agent，file 读回全文 ────────────────
    String treeEnvelope =
        mockMvc
            .perform(get("/api/v1/workspace/tree"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertTrue(treeEnvelope.contains(AGENT_NAME), "S8 tree 的 agents 支应含 " + AGENT_NAME);
    String fileEnvelope =
        mockMvc
            .perform(
                get("/api/v1/workspace/file").param("path", "agents/" + AGENT_NAME + "/AGENT.md"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    assertEquals(
        updated, JSON.readTree(fileEnvelope).path("data").asText(), "S8 file 应只读回读最新 AGENT.md 全文");

    // ── S9 · DELETE：注销 → 移索引 → 归档实证（SC-004）────────────────────────
    int deleteStatus =
        mockMvc
            .perform(delete("/api/v1/agents/" + AGENT_NAME))
            .andReturn()
            .getResponse()
            .getStatus();
    assertEquals(200, deleteStatus, "S9 DELETE 应 200");
    assertTrue(
        Files.isDirectory(workspace.resolve("archive/" + AGENT_NAME)),
        "S9 归档目录应出现在 .yokeos/archive/（不物理删）");
    assertFalse(agentNames().contains(AGENT_NAME), "S9 删除后列表应不含");
    int afterDelete =
        mockMvc.perform(get("/api/v1/agents/" + AGENT_NAME)).andReturn().getResponse().getStatus();
    assertEquals(404, afterDelete, "S9 删除后 GET 应 404");
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  /** 列表名册（解析比对不抛断言——30 节坑表：jsonPath exists 的 AssertionError 是 Error 族）。 */
  private List<String> agentNames() throws Exception {
    String body =
        mockMvc.perform(get("/api/v1/agents")).andReturn().getResponse().getContentAsString();
    return java.util.stream.StreamSupport.stream(
            JSON.readTree(body).path("data").spliterator(), false)
        .map(node -> node.path("name").asText())
        .toList();
  }

  private static String quote(String text) {
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
  }

  private static String head(String text) {
    return text.substring(0, Math.min(60, text.length()));
  }

  private static MockHttpServletRequestBuilder post(String uri) {
    return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(uri);
  }

  private static MockHttpServletRequestBuilder get(String uri) {
    return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(uri);
  }

  private static MockHttpServletRequestBuilder put(String uri) {
    return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(uri);
  }

  private static MockHttpServletRequestBuilder delete(String uri) {
    return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(uri);
  }
}
