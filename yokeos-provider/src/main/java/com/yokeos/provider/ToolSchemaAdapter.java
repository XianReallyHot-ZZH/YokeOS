package com.yokeos.provider;

import com.yokeos.core.tool.YokeTool;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 工具格式适配（宪法 2：Spring AI 只用 schema 生成）：把 {@link YokeTool} 的说明翻译成 Spring AI 1.1.8 的 {@link
 * ToolCallback}——只翻译，产物的 {@code call()} 刻意抛异常（执行权在 ToolExecutor， 第 17 节；1.1.8 无静态 toolDefinitions
 * 列表，定义经 callback 载体随行，research D2 的代差实证）。
 */
public class ToolSchemaAdapter {

  /** 翻译工具清单；入参为空时返回空清单，不带任何私有兜底。 */
  public List<ToolCallback> toSpringAiTools(List<YokeTool> tools) {
    if (tools == null || tools.isEmpty()) {
      return List.of();
    }
    return tools.stream().map(ToolSchemaAdapter::definitionOnlyCallback).toList();
  }

  private static ToolCallback definitionOnlyCallback(YokeTool tool) {
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder()
            .name(tool.getName())
            .description(tool.getDescription())
            .inputSchema(tool.getInputSchema())
            .build();
      }

      @Override
      public String call(String toolInput) {
        // 宪法 2 的第二道闸：翻译产物不可执行。正常路径永远到不了这里（internalToolExecutionEnabled
        // 已关）；若有人改回自动执行，这里抛出而非静默执行。
        throw new UnsupportedOperationException(
            "YokeOS 禁用 Spring AI 自动工具执行（宪法 2）：工具执行归 ToolExecutor（第 17 节）");
      }
    };
  }
}
