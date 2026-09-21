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
 */
@WebMvcTest
@Import({AgentApiController.class, GlobalExceptionHandler.class})
class AgentApiControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ProfileRegistry profileRegistry;

  @MockitoBean private SessionManager sessionManager;

  @MockitoBean private AgentService agentService;

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

  private ResultActions postJson(String uri, String body) throws Exception {
    return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body));
  }
}
