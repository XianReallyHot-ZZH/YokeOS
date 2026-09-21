package com.yokeos.web.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yokeos.core.tool.ToolResult;
import com.yokeos.core.tool.YokeTool;
import com.yokeos.tool.ToolRegistry;
import com.yokeos.web.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** tools 列表切片（第 26 节 T012）：注册表全量投影 name / description。 */
@WebMvcTest
@Import({ToolApiController.class, GlobalExceptionHandler.class})
class ToolApiControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private ToolRegistry toolRegistry;

  @Test
  @DisplayName("列出注册表全量Tool_name与description对齐")
  void listProjectsAllTools() throws Exception {
    YokeTool httpGet = tool("http_get", "发起 HTTP GET 请求");
    YokeTool shell = tool("shell", "执行白名单内的 shell 命令");
    when(toolRegistry.all()).thenReturn(List.of(httpGet, shell));

    mockMvc
        .perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].name").value("http_get"))
        .andExpect(jsonPath("$.data[0].description").value("发起 HTTP GET 请求"))
        .andExpect(jsonPath("$.data[1].name").value("shell"));
  }

  private static YokeTool tool(String name, String description) {
    return new YokeTool() {
      @Override
      public String getName() {
        return name;
      }

      @Override
      public String getDescription() {
        return description;
      }

      @Override
      public String getInputSchema() {
        return "{}";
      }

      @Override
      public ToolResult execute(com.fasterxml.jackson.databind.JsonNode input) {
        return ToolResult.ok("stub"); // 列表端点不触执行——满足接口的最小实现
      }
    };
  }
}
