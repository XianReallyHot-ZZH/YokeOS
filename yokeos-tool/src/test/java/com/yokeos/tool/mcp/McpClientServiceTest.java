package com.yokeos.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.yokeos.core.tool.ToolResult;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.tool.ToolRegistry;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 课件《第 20 节》验收 harness：McpClientServiceTest——失联隔离（最值钱）+ 注册与配置四态 + 单工具重名不连坐。
 *
 * <p>mock McpSyncClient + 工厂注入替身——单测层不起真子进程、不碰真实网络（真 server 归集成冒烟）。
 */
class McpClientServiceTest {

  @TempDir Path dir;

  /** 测试用最小 JSON Schema（adapter 只做映射，内容任意非空即可）。 */
  private static JsonSchema anySchema() {
    return new JsonSchema("object", null, null, null, null, null);
  }

  /** tools/list 返回形态的最小 Tool 规格（1.1.1 七参 record——用 builder，research D3）。 */
  static Tool tool(String name) {
    return Tool.builder().name(name).description(name + " 描述").inputSchema(anySchema()).build();
  }

  /** 直接实现来源的替身（预置重名用，ToolRegistryTest 同款形态）。 */
  private static YokeTool directTool(String name) {
    return new YokeTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return name + " 描述";
      }

      @Override
      public String getInputSchema() {
        return "{\"type\":\"object\"}";
      }

      @Override
      public ToolResult execute(JsonNode input) {
        return ToolResult.ok("ok");
      }
    };
  }

  private McpConfigLoader loaderWith(String yaml) throws IOException {
    Path file = dir.resolve("mcp_servers.yaml");
    Files.writeString(file, yaml);
    return new McpConfigLoader(file);
  }

  private static McpSyncClient clientListing(Tool... tools) {
    McpSyncClient client = mock(McpSyncClient.class);
    when(client.listTools()).thenReturn(new ListToolsResult(List.of(tools), null));
    return client;
  }

  @Test
  @DisplayName("某个MCP_server失联_不能拖垮启动和其他工具")
  void oneMcpServerDownDoesNotBreakStartupOrOtherTools() throws IOException {
    McpConfigLoader loader =
        loaderWith(
            """
            servers:
              - name: good-server
                transport: stdio
                command: good-cmd
              - name: bad-server
                transport: stdio
                command: bad-cmd
            """);
    Function<McpServerConfig, McpSyncClient> factory =
        config -> {
          if ("bad-server".equals(config.name())) {
            throw new IllegalStateException("Connection refused"); // 失联语义
          }
          return clientListing(tool("good_mcp_tool"));
        };
    ToolRegistry registry = new ToolRegistry();

    assertDoesNotThrow(() -> new McpClientService(loader, factory).connectAll(registry));

    assertTrue(registry.contains("good_mcp_tool"), "好 server 的工具照常注册");
    assertFalse(registry.contains("bad_mcp_tool"), "坏 server 的工具零注册");
  }

  @Test
  @DisplayName("listTools的每个工具都被包装注册")
  void allListedToolsAreRegistered() throws IOException {
    McpSyncClient client = clientListing(tool("tool_a"), tool("tool_b"));
    McpConfigLoader loader =
        loaderWith("servers:\n  - name: s\n    transport: stdio\n    command: c\n");
    ToolRegistry registry = new ToolRegistry();

    new McpClientService(loader, config -> client).connectAll(registry);

    assertTrue(registry.contains("tool_a"));
    assertTrue(registry.contains("tool_b"));
  }

  @Test
  @DisplayName("MCP工具与已注册重名_单件跳过不连坐其余工具")
  void duplicateMcpToolSkippedWithoutCollateral() throws IOException {
    // registry 预置 good_mcp_tool（模拟与内置或另一 server 重名）；同 server 还有一个 unique_tool
    McpSyncClient client = clientListing(tool("good_mcp_tool"), tool("unique_tool"));
    McpConfigLoader loader =
        loaderWith("servers:\n  - name: s\n    transport: stdio\n    command: c\n");
    ToolRegistry registry = new ToolRegistry();
    registry.register(directTool("good_mcp_tool"));

    new McpClientService(loader, config -> client).connectAll(registry);

    assertTrue(registry.contains("unique_tool"), "同 server 其余工具照常注册（不连坐）");
    assertEquals(2, registry.all().size(), "预置替身 1 + 新注册 1 = 2（重名件未入册）");
  }

  @Test
  @DisplayName("配置解析_command拆分env占位缺失保留缺文件零server")
  void configLoaderParsesEntriesAndHandlesMissingFile() throws IOException {
    McpConfigLoader loader =
        loaderWith(
            """
            servers:
              - name: github-mcp
                transport: stdio
                command: npx -y server-github
                env:
                  TOKEN: ${YOKEOS_TEST_UNSET_ENV}
            """);

    List<McpServerConfig> configs = loader.load();

    assertEquals(1, configs.size());
    assertTrue(configs.get(0).command().startsWith("npx"), "command 原文保留（拆分归连接层）");
    assertEquals("stdio", configs.get(0).transport());
    assertTrue(configs.get(0).env().get("TOKEN").contains("${YOKEOS_TEST_UNSET_ENV}"), "缺失占位保留原样");
    assertTrue(new McpConfigLoader(dir.resolve("nope.yaml")).load().isEmpty(), "缺文件零 server");
  }

  @Test
  @DisplayName("解析失败按零server处理")
  void invalidYamlYieldsZeroServers() throws IOException {
    McpConfigLoader loader = loaderWith("servers: [ 这不是合法结构");

    assertTrue(loader.load().isEmpty(), "解析失败零 server 不抛出");
  }

  @Test
  @DisplayName("transport非stdio跳过不注册")
  void unsupportedTransportIsSkipped() throws IOException {
    McpConfigLoader loader =
        loaderWith("servers:\n  - name: sse-server\n    transport: sse\n    command: c\n");
    ToolRegistry registry = new ToolRegistry();

    new McpClientService(loader, config -> clientListing(tool("t"))).connectAll(registry);

    assertTrue(registry.all().isEmpty(), "非 stdio 不注册");
  }

  @Test
  @DisplayName("env缺省为空Map且不可变")
  void envDefaultsToEmptyImmutableMap() {
    McpServerConfig config = new McpServerConfig("n", "stdio", "c", null);

    assertEquals(Map.of(), config.env());
  }
}
