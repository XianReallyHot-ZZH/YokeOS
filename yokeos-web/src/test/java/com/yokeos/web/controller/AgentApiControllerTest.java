package com.yokeos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yokeos.core.agent.AgentLifecycleService;
import com.yokeos.core.agent.AgentService;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.ProfileRegistry;
import com.yokeos.core.session.Session;
import com.yokeos.core.session.SessionManager;
import com.yokeos.web.GlobalExceptionHandler;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * invoke 端点切片（第 26 节 T010，教学文档 harness）：未知名 404（先查注册表，坑三）、空白 400、 Controller 薄（process 恰一次）、无状态（两次
 * invoke 各自一次性会话，research D3）、channel=invoke。 显式 Import 被测 Controller 与异常处理器（WebSliceTestBoot 注记）。
 *
 * <p>第 30 节 T006 扩：动态管理 5 端点（generate/create/get 单个与列表/put/delete）——薄转发 lifecycle 恰调一次、错误码对号 （400
 * 已存在与白名单、404 不存在）、AgentView 投影含 agentMarkdown 全文（拍板⑧）；既有 invoke 用例零改动。
 */
@WebMvcTest
@Import({AgentApiController.class, GlobalExceptionHandler.class})
class AgentApiControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProfileRegistry profileRegistry;

  @MockitoBean private SessionManager sessionManager;

  @MockitoBean private AgentService agentService;

  @MockitoBean private AgentLifecycleService lifecycle;

  @Test
  @DisplayName("invoke未注册的Agent_404且先查注册表")
  void invokeUnknownAgent_notFound() throws Exception {
    when(profileRegistry.get("nope")).thenReturn(Optional.empty());

    postJson("/api/v1/agents/nope/invoke", "{\"content\":\"你好\"}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("Agent 不存在: nope"));

    verify(profileRegistry, times(1)).get("nope");
    verify(agentService, never()).process(any(), any());
  }

  @Test
  @DisplayName("invoke空白消息_400")
  void invokeBlankMessage_badRequest() throws Exception {
    postJson("/api/v1/agents/weather/invoke", "{\"content\":\"\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("invoke_走同一编排入口且恰好调一次")
  void invokeDelegatesToProcessExactlyOnce() throws Exception {
    when(profileRegistry.get("weather")).thenReturn(Optional.of(profile("weather")));
    Session session = new Session("invoke:u:weather", "weather");
    when(sessionManager.getOrCreate(eq("invoke"), any(), eq("weather"))).thenReturn(session);
    when(agentService.process(session, "上海天气")).thenReturn("多云");

    postJson("/api/v1/agents/weather/invoke", "{\"content\":\"上海天气\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.reply").value("多云"));

    verify(agentService, times(1)).process(any(), eq("上海天气"));
  }

  @Test
  @DisplayName("两次invoke_各自一次性会话不共享历史")
  void twoInvokes_useDistinctSessions() throws Exception {
    when(profileRegistry.get("weather")).thenReturn(Optional.of(profile("weather")));
    Session first = new Session("invoke:first:weather", "weather");
    Session second = new Session("invoke:second:weather", "weather");
    when(sessionManager.getOrCreate(eq("invoke"), any(), eq("weather"))).thenReturn(first, second);
    when(agentService.process(any(), any())).thenReturn("ok");

    postJson("/api/v1/agents/weather/invoke", "{\"content\":\"第一问\"}").andExpect(status().isOk());
    postJson("/api/v1/agents/weather/invoke", "{\"content\":\"第二问\"}").andExpect(status().isOk());

    ArgumentCaptor<String> users = ArgumentCaptor.forClass(String.class);
    verify(sessionManager, times(2)).getOrCreate(eq("invoke"), users.capture(), eq("weather"));
    List<String> distinctUsers = users.getAllValues();
    org.junit.jupiter.api.Assertions.assertNotEquals(
        distinctUsers.get(0), distinctUsers.get(1), "每次 invoke 的 user 唯一（无状态，research D3）");
    org.junit.jupiter.api.Assertions.assertEquals(2, distinctUsers.size());
    verify(agentService, times(2)).process(any(), any());
  }

  private static Profile profile(String name) {
    return new Profile(name, null, null, null, null, null, null, null, null, null, null, null);
  }

  /** 带完整展示字段的 Profile（AgentView 投影断言用）。 */
  private static Profile richProfile(String name) {
    return new Profile(
        name,
        "天气助手",
        null,
        new Profile.ProviderConfig("deepseek", "deepseek-chat", null),
        List.of("http_get", "notify"),
        null,
        null,
        null,
        null,
        List.of(new Profile.ScheduleConfig("morning", "0 8 * * *", null, "早报")),
        null,
        null);
  }

  @Test
  @DisplayName("create_薄转发lifecycle恰一次_AgentView投影含全文")
  void createDelegatesToLifecycle() throws Exception {
    when(lifecycle.create(eq("demo"), any())).thenReturn(richProfile("demo"));

    postJson(
            "/api/v1/agents",
            "{\"name\":\"demo\",\"agentMarkdown\":\"---\\nname: demo\\n---\\n正文\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("demo"))
        .andExpect(jsonPath("$.data.description").value("天气助手"))
        .andExpect(jsonPath("$.data.provider").value("deepseek"))
        .andExpect(jsonPath("$.data.model").value("deepseek-chat"))
        .andExpect(jsonPath("$.data.hasSchedules").value(true));

    verify(lifecycle, times(1)).create(eq("demo"), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  @DisplayName("create_name冲突_400透传消息")
  void createNameConflictBadRequest() throws Exception {
    when(lifecycle.create(eq("demo"), any()))
        .thenThrow(new IllegalArgumentException("Agent 已存在: demo"));

    postJson("/api/v1/agents", "{\"name\":\"demo\",\"agentMarkdown\":\"x\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Agent 已存在: demo"));
  }

  @Test
  @DisplayName("create_name白名单外_400且lifecycle零调用（穿越形态一）")
  void createInvalidNameTraversalBadRequest() throws Exception {
    postJson("/api/v1/agents", "{\"name\":\"../evil\",\"agentMarkdown\":\"x\"}")
        .andExpect(status().isBadRequest());

    verify(lifecycle, never()).create(any(), any());
  }

  @Test
  @DisplayName("create_name带空格_400（白名单边界）")
  void createInvalidNameWithSpaceBadRequest() throws Exception {
    postJson("/api/v1/agents", "{\"name\":\"a b\",\"agentMarkdown\":\"x\"}")
        .andExpect(status().isBadRequest());

    verify(lifecycle, never()).create(any(), any());
  }

  @Test
  @DisplayName("create_定义非法_400可读原因")
  void createInvalidMarkdownBadRequest() throws Exception {
    when(lifecycle.create(eq("demo"), any()))
        .thenThrow(new IllegalArgumentException("AGENT.md frontmatter 未闭合（缺少第二个 ---）"));

    postJson("/api/v1/agents", "{\"name\":\"demo\",\"agentMarkdown\":\"垃圾\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("AGENT.md frontmatter 未闭合（缺少第二个 ---）"));
  }

  @Test
  @DisplayName("list_列出全部Agent")
  void listReturnsAllAgents() throws Exception {
    when(lifecycle.list()).thenReturn(List.of(richProfile("demo"), profile("bare")));

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/agents"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].name").value("demo"))
        .andExpect(jsonPath("$.data[1].hasSchedules").value(false));
  }

  @Test
  @DisplayName("get单个_200含agentMarkdown全文（编辑回填）")
  void getAgentReturnsMarkdown() throws Exception {
    Profile rich = richProfile("demo");
    when(lifecycle.get("demo")).thenReturn(Optional.of(rich));
    when(lifecycle.readMarkdown("demo")).thenReturn("---\nname: demo\n---\n正文");

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/agents/demo"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("demo"))
        .andExpect(jsonPath("$.data.agentMarkdown").value("---\nname: demo\n---\n正文"));
  }

  @Test
  @DisplayName("get单个_不存在404")
  void getUnknownAgentNotFound() throws Exception {
    when(lifecycle.get("ghost")).thenReturn(Optional.empty());

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/agents/ghost"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("update_薄转发_200返回新视图")
  void updateDelegatesToLifecycle() throws Exception {
    when(lifecycle.get("demo")).thenReturn(Optional.of(profile("demo")));
    when(lifecycle.update(eq("demo"), any())).thenReturn(richProfile("demo"));

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/agents/demo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentMarkdown\":\"---\\nname: demo\\n---\\n新正文\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.name").value("demo"));

    verify(lifecycle, times(1)).update(eq("demo"), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  @DisplayName("update_不存在404且不触update")
  void updateUnknownAgentNotFound() throws Exception {
    when(lifecycle.get("ghost")).thenReturn(Optional.empty());

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/agents/ghost")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentMarkdown\":\"x\"}"))
        .andExpect(status().isNotFound());

    verify(lifecycle, never()).update(any(), any());
  }

  @Test
  @DisplayName("update_定义非法_400旧定义不破坏")
  void updateInvalidMarkdownBadRequest() throws Exception {
    when(lifecycle.get("demo")).thenReturn(Optional.of(profile("demo")));
    when(lifecycle.update(eq("demo"), any()))
        .thenThrow(new IllegalArgumentException("AGENT.md 缺少 frontmatter 围栏（首行必须是 ---）"));

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/agents/demo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentMarkdown\":\"垃圾\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("AGENT.md 缺少 frontmatter 围栏（首行必须是 ---）"));
  }

  @Test
  @DisplayName("delete_先404判定再薄转发")
  void deleteDelegatesAfterExistenceCheck() throws Exception {
    when(lifecycle.get("demo")).thenReturn(Optional.of(profile("demo")));

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/v1/agents/demo"))
        .andExpect(status().isOk());

    verify(lifecycle, times(1)).delete("demo");
  }

  @Test
  @DisplayName("delete_不存在404且不触delete")
  void deleteUnknownAgentNotFound() throws Exception {
    when(lifecycle.get("ghost")).thenReturn(Optional.empty());

    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                "/api/v1/agents/ghost"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));

    verify(lifecycle, never()).delete(any());
  }

  @Test
  @DisplayName("generate_200返回草稿不落盘")
  void generateReturnsDraft() throws Exception {
    when(lifecycle.generate("每天早上九点查天气")).thenReturn("---\nname: x\n---\n正文");

    postJson("/api/v1/agents/generate", "{\"sentence\":\"每天早上九点查天气\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.agentMarkdown").value("---\nname: x\n---\n正文"));

    verify(lifecycle, times(1)).generate("每天早上九点查天气");
  }

  @Test
  @DisplayName("generate_空句400")
  void generateBlankSentenceBadRequest() throws Exception {
    when(lifecycle.generate("")).thenThrow(new IllegalArgumentException("生成需求不能为空"));

    postJson("/api/v1/agents/generate", "{\"sentence\":\"\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("生成需求不能为空"));
  }

  @Test
  @DisplayName("generate_配置缺失503消息含配置方法")
  void generateMissingConfigServiceUnavailable() throws Exception {
    when(lifecycle.generate(any()))
        .thenThrow(
            new IllegalStateException(
                "未配置生成用 provider——请在 application.yaml 配置 yokeos.agent-generation.provider"));

    postJson("/api/v1/agents/generate", "{\"sentence\":\"一句话\"}")
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value(503));
  }

  private ResultActions postJson(String uri, String body) throws Exception {
    return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body));
  }
}
