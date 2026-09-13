package com.yokeos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yokeos.core.tool.YokeTool;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;

/** 工具格式适配（宪法 2 只用 schema 生成）：只翻译、产物不可执行。 */
class ToolSchemaAdapterTest {

  private static final String SCHEMA =
      "{\"type\":\"object\",\"properties\":{\"url\":{\"type\":\"string\"}},\"required\":[\"url\"]}";

  @Test
  @DisplayName("schema翻译_定义字段一一对齐")
  void schemaFieldsAlignedAfterTranslation() {
    var out = new ToolSchemaAdapter().toSpringAiTools(List.of(httpGetTool()));

    assertEquals(1, out.size());
    ToolDefinition def = out.get(0).getToolDefinition();
    assertEquals("http_get", def.name());
    assertEquals("发起一次 HTTP GET 请求", def.description());
    assertEquals(SCHEMA, def.inputSchema());
  }

  @Test
  @DisplayName("只翻译_产物call入口抛异常不可执行")
  void translationCarriesNoExecutionLogic() {
    var out = new ToolSchemaAdapter().toSpringAiTools(List.of(httpGetTool()));

    // 宪法 2 第二道闸的钉子：翻译产物的执行入口必须拒绝——就算有人改回自动执行，也执行不了
    assertThrows(
        UnsupportedOperationException.class, () -> out.get(0).call("{\"url\":\"https://a\"}"));
  }

  static YokeTool httpGetTool() {
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
        return SCHEMA;
      }

      @Override
      public com.yokeos.core.tool.ToolResult execute(
          com.fasterxml.jackson.databind.JsonNode input) {
        throw new UnsupportedOperationException("测试桩不执行（第 17 节起接口含 execute）");
      }
    };
  }
}
