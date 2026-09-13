package com.yokeos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yokeos.core.audit.LlmCallAuditor;
import com.yokeos.core.profile.Profile;
import com.yokeos.core.profile.Profile.Identity;
import com.yokeos.core.profile.Profile.ProviderConfig;
import com.yokeos.core.profile.Profile.Settings;
import com.yokeos.core.provider.ProviderRequest;
import com.yokeos.core.provider.ProviderResponse;
import com.yokeos.core.provider.ToolCallRequest;
import com.yokeos.core.tool.YokeTool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/**
 * 第 16 节关键回归 harness（原 ProviderServiceTest 改名平移，第 17
 * 节契约上移拍板①）：坑一显式映射、坑二自动执行关闭、失败审计路径——每个坑一个回归测试钉死，断言语义逐条保留。 第 17 节补 ChatResponse → ProviderResponse
 * 映射用例（research D2 实证写法）。
 */
class SpringAiProviderServiceTest {

  private final ChatModel deepseek = mock(ChatModel.class);
  private final ChatModel kimi = mock(ChatModel.class);
  private final LlmCallAuditor auditor = mock(LlmCallAuditor.class);
  private final SpringAiProviderService service =
      new SpringAiProviderService(
          Map.of("deepseek", deepseek, "kimi", kimi), new ToolSchemaAdapter(), auditor);

  private final ProviderRequest request = new ProviderRequest("今天北京天气如何？", List.of());

  @Test
  @DisplayName("按名路由_两个provider不串台")
  void routeByNameTwoProvidersNoCrosstalk() {
    service.chat("s-1", profileUsing("kimi"), request);

    verify(kimi, times(1)).call(any(org.springframework.ai.chat.prompt.Prompt.class)); // 调的是 kimi
    verify(deepseek, org.mockito.Mockito.never())
        .call(any(org.springframework.ai.chat.prompt.Prompt.class)); // deepseek 全程零调用——不串台
  }

  @Test
  @DisplayName("引用未知名_抛异常且消息含缺失名字")
  void callWithUnknownProviderThrowsWithNameInMessage() {
    var ex =
        assertThrows(
            ProviderNotFoundException.class,
            () -> service.chat("s-1", profileUsing("nope"), request));

    assertTrue(ex.getMessage().contains("nope"), "报错必须指出是哪个名字找不到");
    verifyNoInteractions(deepseek, kimi);
  }

  @Test
  @DisplayName("带工具schema调用_请求里关闭了自动执行")
  void callWithToolSchemaDisablesAutoExecution() {
    service.chat("s-1", profileUsing("deepseek"), new ProviderRequest("q", List.of(httpGetTool())));

    var captor = ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
    verify(deepseek).call(captor.capture());
    var options = assertInstanceOf(ToolCallingChatOptions.class, captor.getValue().getOptions());
    assertEquals(
        Boolean.FALSE,
        options.getInternalToolExecutionEnabled(), // 坑二回归：一旦改回自动执行这里立刻红（宪法 2）
        "Spring AI 自动工具执行必须显式关闭");
    assertFalse(options.getToolCallbacks().isEmpty(), "翻译过的工具 schema 必须随请求带上");
    assertEquals("http_get", options.getToolCallbacks().get(0).getToolDefinition().name());
  }

  @Test
  @DisplayName("调用失败_审计必须留下success为false的记录")
  void callFailureAuditsSuccessFalseRecord() {
    when(deepseek.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenThrow(new RuntimeException("connect timeout"));

    var ex =
        assertThrows(
            RuntimeException.class, () -> service.chat("s-1", profileUsing("deepseek"), request));

    assertEquals("connect timeout", ex.getMessage()); // 异常继续上抛
    verify(auditor)
        .record(
            eq("s-1"),
            eq("deepseek"),
            eq("test-model"),
            isNull(),
            isNull(),
            isNull(),
            eq(false),
            contains("timeout"), // 但审计先落了账：success=false + 原因（宪法 7）
            anyLong());
  }

  @Test
  @DisplayName("调用成功_审计落success为true且带token用量")
  void callSuccessAuditsWithUsage() {
    Usage usage = mock(Usage.class);
    when(usage.getPromptTokens()).thenReturn(120);
    when(usage.getCompletionTokens()).thenReturn(80);
    when(usage.getTotalTokens()).thenReturn(200);
    ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
    when(metadata.getUsage()).thenReturn(usage);
    ChatResponse resp = mock(ChatResponse.class);
    when(resp.getMetadata()).thenReturn(metadata);
    when(resp.getResult()).thenReturn(new Generation(new AssistantMessage("你好，我是运维助手")));
    when(kimi.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(resp);

    ProviderResponse out = service.chat("s-2", profileUsing("kimi"), request);

    assertEquals("你好，我是运维助手", out.text(), "响应文本经 ProviderResponse 原样返回上层（含 tool call 请求，本模块零执行）");
    verify(auditor)
        .record(
            eq("s-2"),
            eq("kimi"),
            eq("test-model"),
            eq(120),
            eq(80),
            eq(200),
            eq(true),
            isNull(),
            anyLong());
  }

  @Test
  @DisplayName("模型提出多个工具调用_逐项映射不丢")
  void toolCallsExtractedNotLost() {
    AssistantMessage output = mock(AssistantMessage.class);
    when(output.getText()).thenReturn(null);
    when(output.getToolCalls())
        .thenReturn(
            List.of(
                new AssistantMessage.ToolCall(
                    "call-1", "function", "http_get", "{\"url\":\"https://a\"}"),
                new AssistantMessage.ToolCall(
                    "call-2", "function", "http_get", "{\"url\":\"https://b\"}")));
    ChatResponse resp = mock(ChatResponse.class);
    when(resp.getResult()).thenReturn(new Generation(output));
    when(resp.getMetadata()).thenReturn(mock(ChatResponseMetadata.class));
    when(deepseek.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(resp);

    ProviderResponse out = service.chat("s-1", profileUsing("deepseek"), request);

    assertTrue(out.hasToolCalls(), "有工具调用请求时 hasToolCalls 必须为 true");
    assertEquals(2, out.toolCalls().size(), "两个调用请求一个不丢");
    assertEquals(
        List.of(
            new ToolCallRequest("http_get", "{\"url\":\"https://a\"}"),
            new ToolCallRequest("http_get", "{\"url\":\"https://b\"}")),
        out.toolCalls(),
        "name 与 argumentsJson 逐项映射");
  }

  @Test
  @DisplayName("模型只提工具调用_text为null不炸映射")
  void textNullSafe() {
    AssistantMessage output = mock(AssistantMessage.class);
    when(output.getText()).thenReturn(null);
    when(output.getToolCalls())
        .thenReturn(List.of(new AssistantMessage.ToolCall("c", "function", "http_get", "{}")));
    ChatResponse resp = mock(ChatResponse.class);
    when(resp.getResult()).thenReturn(new Generation(output));
    when(resp.getMetadata()).thenReturn(mock(ChatResponseMetadata.class));
    when(deepseek.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(resp);

    ProviderResponse out = service.chat("s-1", profileUsing("deepseek"), request);

    assertNull(out.text(), "text 保持 null（空串归 ReActLoop 收尾处理）");
    assertTrue(out.hasToolCalls());
  }

  @Test
  @DisplayName("无工具调用时_hasToolCalls为false")
  void noToolCallsHasToolCallsFalse() {
    ChatResponse resp = mock(ChatResponse.class);
    when(resp.getResult()).thenReturn(new Generation(new AssistantMessage("直接回答")));
    when(resp.getMetadata()).thenReturn(mock(ChatResponseMetadata.class));
    when(deepseek.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(resp);

    ProviderResponse out = service.chat("s-1", profileUsing("deepseek"), request);

    assertFalse(out.hasToolCalls(), "判停依据：无工具调用请求即收尾");
    assertTrue(out.toolCalls().isEmpty());
  }

  private static Profile profileUsing(String providerName) {
    return new Profile(
        "ops-agent",
        "运维助手",
        new Identity("运维小欧", "你是一个专业的运维助手"),
        new ProviderConfig(providerName, "test-model", 0.7),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Settings.DEFAULT);
  }

  private static YokeTool httpGetTool() {
    return new YokeTool() {
      @Override
      public String getName() {
        return "http_get";
      }

      @Override
      public String getDescription() {
        return "发起一次 HTTP GET 请求";
      }

      @Override
      public String getInputSchema() {
        return "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},"
            + "\"required\":[\"url\"]}";
      }

      @Override
      public com.yokeos.core.tool.ToolResult execute(
          com.fasterxml.jackson.databind.JsonNode input) {
        throw new UnsupportedOperationException("测试桩不执行");
      }
    };
  }
}
