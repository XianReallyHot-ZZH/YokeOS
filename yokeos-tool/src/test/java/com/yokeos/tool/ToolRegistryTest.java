package com.yokeos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.yokeos.core.tool.ToolResult;
import com.yokeos.core.tool.YokeTool;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 课件《第 20 节》验收 harness：ToolRegistryTest——三种来源统一注册、重名拒绝、过滤恰好。
 *
 * <p>「不多不少」的讲究：按 Profile.tools 过滤的结果恰好等于声明∩注册面——多一个（没过滤干净）和少一个（过滤过头）都是错。
 */
class ToolRegistryTest {

  /** 直接实现来源的替身（NotifyTools 同形态）。 */
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

  /**
   * @Tool 注解来源的替身 bean（方式三与内置同管道）。
   */
  static class AnnotatedBean {
    @org.springframework.ai.tool.annotation.Tool(name = "annotated_tool", description = "注解来源工具")
    public String annotatedTool(
        @org.springframework.ai.tool.annotation.ToolParam(description = "入参") String input) {
      return input;
    }
  }

  /** MCP 形态的替身：mock ToolCallback 经 AnnotatedToolAdapter 包装（McpToolAdapter 同构造形态）。 */
  private static YokeTool annotatedTool(String name) {
    ToolCallback callback = mock(ToolCallback.class);
    ToolDefinition definition = mock(ToolDefinition.class);
    when(definition.name()).thenReturn(name);
    when(definition.description()).thenReturn(name + " 描述");
    when(definition.inputSchema()).thenReturn("{\"type\":\"object\"}");
    when(callback.getToolDefinition()).thenReturn(definition);
    return new AnnotatedToolAdapter(callback);
  }

  @Test
  @DisplayName("三种来源的工具都以YokeTool身份注册进来")
  void allThreeSourcesRegisterAsYokeTool() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(directTool("notify"));
    registry.registerAnnotated(new AnnotatedBean());
    registry.register(annotatedTool("github_search"));

    assertTrue(registry.contains("notify"));
    assertTrue(registry.contains("annotated_tool"));
    assertTrue(registry.contains("github_search"));
    assertEquals(3, registry.all().size());
    assertEquals(3, registry.asMap().size());
    assertEquals(Optional.of("notify"), registry.get("notify").map(YokeTool::getName));
  }

  @Test
  @DisplayName("重名注册被拒绝并点名")
  void duplicateNameIsRejected() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(directTool("shell"));

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> registry.register(directTool("shell")));

    assertTrue(ex.getMessage().contains("shell"), "报错必须点名重名工具");
    assertEquals(1, registry.all().size(), "先注册者不受影响");
  }

  @Test
  @DisplayName("按Profile的tools字段过滤_子集恰好等于声明列表")
  void filterByProfileToolsIsExact() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(directTool("read_file"));
    registry.register(directTool("http_get"));
    registry.register(directTool("shell"));

    List<YokeTool> filtered =
        registry.filterByNames(List.of("read_file", "http_get", "not_registered"));

    // 不多（shell 没混进来）不少（两个声明的都在）；未知名跳过不报错
    assertEquals(2, filtered.size());
    assertEquals("read_file", filtered.get(0).getName());
    assertEquals("http_get", filtered.get(1).getName());
    assertFalse(filtered.stream().anyMatch(t -> "shell".equals(t.getName())));
  }

  @Test
  @DisplayName("filterByNames空清单返回空列表_全部声明顺序保留")
  void filterByNamesEmptyAndOrderKept() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(directTool("read_file"));
    registry.register(directTool("write_file"));

    assertTrue(registry.filterByNames(List.of()).isEmpty());
    assertEquals(
        List.of("write_file", "read_file"),
        registry.filterByNames(List.of("write_file", "read_file")).stream()
            .map(YokeTool::getName)
            .toList());
  }
}
