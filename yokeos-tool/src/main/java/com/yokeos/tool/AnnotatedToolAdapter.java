package com.yokeos.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yokeos.core.tool.ToolResult;
import com.yokeos.core.tool.YokeTool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.execution.ToolExecutionException;

/**
 * 注解管道的机制本体（{@code @Tool} 注解方法）：把 {@link ToolCallback}（schema 已由 Spring AI 自动生成）包装成 {@link
 * YokeTool}——内置工具与业务方方式三共用这条管道（技 §6.5/§6.6）。
 *
 * <p>宪法 2 边界：这里只借 Spring AI 的 schema 生成与方法反射调用；{@code callback.call()} 是进程内直调（方式三的语义本体）， 执行的发起方永远是第
 * 17 节的 ToolExecutor——不存在框架自动执行路径。异常不吞语义（坑一）：adapter 里兜成成功结果会让 tool_invocations 永远记成功。
 *
 * <p>1.1.8 管道形态适配（specs/005 实证）：MethodToolCallback 会把 String 返回值序列化成 JSON 字符串字面量（{@code "yoke"}
 * 带引号）、 把方法异常包成 ToolExecutionException——两者都在此剥壳，保证 {@link YokeTool} 契约的返回文本与异常类型不被管道污染（引号不进对话历史与审计、
 * 工具层异常类型原样上抛给 ToolExecutor 转失败结果）。
 */
public class AnnotatedToolAdapter implements YokeTool {

  /** 线程安全（Jackson 官方口径），静态复用免每次重建。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  private final ToolCallback callback;

  /**
   * @param callback MethodToolCallbackProvider 生成的载体（schema 已含）。
   */
  public AnnotatedToolAdapter(ToolCallback callback) {
    this.callback = callback;
  }

  @Override
  public String getName() {
    return callback.getToolDefinition().name();
  }

  @Override
  public String getDescription() {
    return callback.getToolDefinition().description();
  }

  @Override
  public String getInputSchema() {
    return callback.getToolDefinition().inputSchema();
  }

  @Override
  public ToolResult execute(JsonNode input) {
    // 进程内直调（宪法 2）
    String output;
    try {
      output = callback.call(input == null ? "{}" : input.toString());
    } catch (ToolExecutionException e) {
      // 剥管道壳：还原方法本体的异常类型与消息（cause 缺失的极端形态原样上抛）
      if (e.getCause() instanceof RuntimeException runtimeCause) {
        throw runtimeCause;
      }
      throw e;
    }
    return ToolResult.ok(unwrapJsonStringLiteral(output));
  }

  /** 剥 JSON 字符串字面量包装：{@code "yoke"} → {@code yoke}；非该形态（数字/对象/普通文本）原样返回。 */
  private static String unwrapJsonStringLiteral(String output) {
    if (output == null
        || output.length() < 2
        || output.charAt(0) != '"'
        || output.charAt(output.length() - 1) != '"') {
      return output;
    }
    try {
      return JSON.readValue(output, String.class);
    } catch (JsonProcessingException e) {
      return output; // 形似而非合法 JSON 字面量（如内含未转义引号）——原样返回不猜
    }
  }
}
