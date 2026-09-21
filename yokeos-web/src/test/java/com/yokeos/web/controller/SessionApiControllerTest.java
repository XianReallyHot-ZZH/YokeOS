package com.yokeos.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yokeos.core.agent.AgentService;
import com.yokeos.core.session.Message;
import com.yokeos.core.session.Session;
import com.yokeos.core.session.SessionManager;
import com.yokeos.core.session.SessionSummary;
import com.yokeos.web.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 会话五端点切片（第 26 节 T007，教学文档 harness）：校验 400 / 三路 404 / Controller 薄（process 恰一次） / 历史截断 ≤100 / 列表过滤。
 * 只起 MVC 层，编排与会话服务全 mock（坑四分层）；显式 Import 被测 Controller 与异常处理器（WebSliceTestBoot 注记）。
 */
@WebMvcTest
@Import({SessionApiController.class, GlobalExceptionHandler.class})
class SessionApiControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private SessionManager sessionManager;

  @MockitoBean private AgentService agentService;

  @Test
  @DisplayName("创建会话缺profile_400")
  void createMissingProfile_badRequest() throws Exception {
    postJson("/api/v1/sessions", "{\"userId\":\"u1\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
    postJson("/api/v1/sessions", "{}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("创建会话_返回sessionId且缺省user")
  void createReturnsSessionIdWithDefaultUser() throws Exception {
    when(sessionManager.getOrCreate("web", "default", "weather"))
        .thenReturn(new Session("web:default:weather", "weather"));

    postJson("/api/v1/sessions", "{\"profile\":\"weather\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value("web:default:weather"));
  }

  @Test
  @DisplayName("发消息_空白内容400")
  void sendBlankMessage_badRequest() throws Exception {
    postJson("/api/v1/sessions/s-1/messages", "{\"content\":\"\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("发消息_超过32KB_400")
  void sendOversizedMessage_badRequest() throws Exception {
    String oversized = "a".repeat(32 * 1024 + 1);
    postJson("/api/v1/sessions/s-1/messages", "{\"content\":\"" + oversized + "\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400));
  }

  @Test
  @DisplayName("发消息_会话不存在404")
  void sendToUnknownSession_notFound() throws Exception {
    when(sessionManager.get("s-404")).thenReturn(Optional.empty());

    postJson("/api/v1/sessions/s-404/messages", "{\"content\":\"你好\"}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404))
        .andExpect(jsonPath("$.message").value("会话不存在: s-404"));
  }

  @Test
  @DisplayName("查历史_会话不存在404")
  void getUnknownSession_notFound() throws Exception {
    when(sessionManager.get("s-404")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/sessions/s-404"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("归档_会话不存在404")
  void archiveUnknownSession_notFound() throws Exception {
    when(sessionManager.archive("s-404")).thenReturn(false);

    mockMvc
        .perform(delete("/api/v1/sessions/s-404"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  @Test
  @DisplayName("发消息_走同一编排入口且恰好调一次")
  void sendMessage_delegatesToProcessExactlyOnce() throws Exception {
    Session session = new Session("web:u1:weather", "weather");
    when(sessionManager.get("web:u1:weather")).thenReturn(Optional.of(session));
    when(agentService.process(session, "今天北京天气怎么样")).thenReturn("晴");

    postJson("/api/v1/sessions/web:u1:weather/messages", "{\"content\":\"今天北京天气怎么样\"}")
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.reply").value("晴"));

    verify(agentService, times(1)).process(any(), eq("今天北京天气怎么样"));
  }

  @Test
  @DisplayName("查历史_最多返回最近100条")
  void historyTruncatedToHundred() throws Exception {
    List<Message> hundredFive =
        IntStream.rangeClosed(1, 105)
            .mapToObj(i -> new Message("user", "第" + i + "句", null))
            .toList();
    Session session = new Session("s-1", "weather", hundredFive);
    when(sessionManager.get("s-1")).thenReturn(Optional.of(session));

    mockMvc
        .perform(get("/api/v1/sessions/s-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.messages.length()").value(100))
        .andExpect(jsonPath("$.data.messages[0].content").value("第6句"));
  }

  @Test
  @DisplayName("会话列表_status过滤生效且无参全量")
  void listFiltersByStatus() throws Exception {
    SessionSummary active =
        new SessionSummary("web:u1:weather", "weather", "web", "u1", "active", LocalDateTime.now());
    SessionSummary archived =
        new SessionSummary(
            "web:u2:weather", "weather", "web", "u2", "archived", LocalDateTime.now());
    when(sessionManager.listRecent(100)).thenReturn(List.of(active, archived));

    mockMvc
        .perform(get("/api/v1/sessions").param("status", "active"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].status").value("active"));

    mockMvc
        .perform(get("/api/v1/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2));
  }

  @Test
  @DisplayName("归档成功_返回archived为true")
  void archiveSucceeds() throws Exception {
    when(sessionManager.archive("s-1")).thenReturn(true);

    mockMvc
        .perform(delete("/api/v1/sessions/s-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.archived").value(true));
  }

  private ResultActions postJson(String uri, String body) throws Exception {
    return mockMvc.perform(post(uri).contentType(MediaType.APPLICATION_JSON).content(body));
  }
}
