package com.yokeos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yokeos.core.tool.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 课件《第 20 节》验收 harness：McpToolAdapterTest——三要素映射、参数原样转发、结果包装与可重试。
 *
 * <p>Tool 规格用 builder 构造（1.1.1 为七参 record，课件三参构造是 0.x 旧形态——specs/005 research D3）。
 */
class McpToolAdapterTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private McpSyncClient client;

  private McpToolAdapter adapter;

  @BeforeEach
  void setUp() {
    client = mock(McpSyncClient.class);
    adapter =
        new McpToolAdapter(
            client,
            Tool.builder()
                .name("github_search")
                .description("搜 GitHub")
                .inputSchema(new JsonSchema("object", null, null, null, null, null))
                .build());
  }

  @Test
  @DisplayName("三要素直接映射tools_list返回")
  void contractMapsFromMcpToolSpec() {
    assertEquals("github_search", adapter.getName());
    assertEquals("搜 GitHub", adapter.getDescription());
    assertTrue(adapter.getInputSchema().contains("object"), "schema 序列化含类型定义");
  }

  @Test
  @DisplayName("execute原样转发参数并包装成功结果")
  void executeForwardsArgsAndWrapsResult() throws Exception {
    when(client.callTool(any()))
        .thenReturn(
            new CallToolResult(List.of(new TextContent("found 3 repos")), false, null, null));

    ToolResult result = adapter.execute(MAPPER.readTree("{\"query\":\"yokeos\"}"));
    assertTrue(result.success());
    assertEquals("found 3 repos", result.content());

    ArgumentCaptor<CallToolRequest> captor = ArgumentCaptor.forClass(CallToolRequest.class);
    verify(client).callTool(captor.capture());
    assertEquals("github_search", captor.getValue().name(), "工具名原样");
    assertEquals(Map.of("query", "yokeos"), captor.getValue().arguments(), "参数逐键原样");
  }

  @Test
  @DisplayName("MCP调用失败_包装为可重试的失败结果")
  void mcpErrorWrapsAsRetryableFailure() throws Exception {
    when(client.callTool(any()))
        .thenReturn(new CallToolResult(List.of(new TextContent("rate limited")), true, null, null));

    ToolResult result = adapter.execute(MAPPER.readTree("{}"));

    assertFalse(result.success());
    assertTrue(result.retryable(), "网络/限流类失败值得循环再试一次");
    assertTrue(result.errorMessage().contains("rate limited"), "错误信息含 server 给出的内容");
  }

  @Test
  @DisplayName("多段文本内容逐段拼接")
  void multiSegmentContentJoined() throws Exception {
    when(client.callTool(any()))
        .thenReturn(
            new CallToolResult(
                List.of(new TextContent("a"), new TextContent("b")), false, null, null));

    String content = adapter.execute(MAPPER.readTree("{}")).content();

    assertEquals("a\nb", content);
  }
}
